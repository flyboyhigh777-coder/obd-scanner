package com.obdscanner.app

import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Сквозной прогон НАСТОЯЩЕГО приложения (MainActivity + Compose + ViewModel +
 * :core) на JVM через Robolectric в демо-режиме: подключение к виртуальному ЭБУ,
 * приборы, чтение и сброс ошибок, данные автомобиля, запись поездки. На каждом
 * экране снимается скриншот в app/build/outputs/roborazzi (CI выкладывает их в
 * ветку ci-screenshots).
 *
 * Демо-режим здесь не "заглушка ради теста": это тот же симулятор ЭБУ, которым
 * проверяется протокол в :core, и то же, что видит пользователь, выбрав "Демо" -
 * значит тест гоняет ровно рабочий путь, а не отдельную тестовую ветку.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class ObdAppFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val outDir = File("build/outputs/roborazzi").apply { mkdirs() }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)
    }

    /**
     * В Robolectric главный поток - это поток теста, и `compose.waitUntil` просто
     * спит, не прокручивая очередь главного потока. Корутины, которые приносят
     * данные из Dispatchers.IO в состояние экрана, при этом не продвигаются -
     * поэтому очередь прокручивается здесь явно.
     */
    private fun pumpUntil(timeoutMillis: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) {
                runCatching { shot("99_timeout") }
                throw AssertionError("Не дождались: $what (${timeoutMillis} мс)")
            }
            Thread.sleep(20)
        }
    }

    /** Переключение вкладки: подпись есть и в нижней панели, и иногда в заголовке - панель последняя. */
    private fun openTab(title: String) {
        compose.onAllNodesWithText(title).onLast().performClick()
        compose.waitForIdle()
    }

    @Test
    fun demoDriveThroughEveryScreen() {
        val vm = ViewModelProvider(compose.activity)[ObdViewModel::class.java]

        shot("01_connect")

        // Подключаемся к виртуальной машине.
        compose.onNodeWithText("Демо").performClick()
        compose.waitForIdle()
        shot("02_connect_demo_selected")
        compose.onNodeWithText("Подключиться").performClick()
        pumpUntil(30_000, "связь с адаптером") { vm.connection.value is ConnectionState.Connected }

        val connected = vm.connection.value as ConnectionState.Connected
        assertTrue("адаптер представился: ${connected.adapterId}", connected.adapterId.contains("ELM327"))
        assertNotNull("протокол должен определиться", connected.protocol)
        assertTrue("машина отдала список параметров: ${connected.pidCount}", connected.pidCount > 20)
        shot("03_connect_connected")

        // Приборы: ждём первые значения и проверяем, что они связаны между собой.
        openTab("Приборы")
        pumpUntil(20_000, "первые данные приборов") { vm.live.value.hasData }
        val values = vm.live.value.values
        assertTrue("обороты есть: $values", values.containsKey(0x0C))
        assertTrue("температура ОЖ есть", values.containsKey(0x05))
        assertTrue("обороты в разумных пределах: ${values[0x0C]}", values[0x0C]!! in 500.0..7000.0)
        assertNotNull("лампа Check Engine прочитана", vm.live.value.monitor)
        assertTrue("виртуальная машина с горящей лампой", vm.live.value.monitor!!.milOn)
        shot("04_dashboard")

        // Ошибки: читаем все три режима и стоп-кадр.
        openTab("Ошибки")
        shot("05_dtc_empty")
        compose.onNodeWithText("Считать ошибки").performClick()
        pumpUntil(30_000, "чтение кодов ошибок") { vm.dtc.value.loaded }
        val dtc = vm.dtc.value
        assertEquals(listOf("P0301", "P0171", "P0420"), dtc.stored.map { it.code })
        assertEquals(listOf("P0133"), dtc.pending.map { it.code })
        assertEquals(listOf("P0420"), dtc.permanent.map { it.code })
        assertTrue("стоп-кадр заполнен: ${dtc.freezeFrame}", dtc.freezeFrame.isNotEmpty())
        assertNotNull(dtc.monitor)
        // Расшифровка на русском должна быть видна на экране, а не только в коде.
        compose.onNodeWithText("P0301").assertExists()
        shot("06_dtc_list")
        compose.onNodeWithText("Бортовые тесты").performScrollTo()
        shot("07_dtc_monitors")

        // Сброс: предупреждение, подтверждение, и постоянный код остаётся.
        compose.onNodeWithText("Сбросить ошибки").performScrollTo().performClick()
        compose.waitForIdle()
        shot("08_dtc_clear_dialog")
        compose.onNodeWithText("Сбросить").performClick()
        pumpUntil(30_000, "сброс ошибок") { vm.dtc.value.stored.isEmpty() && !vm.dtc.value.reading }
        assertTrue("ожидающие тоже стёрты", vm.dtc.value.pending.isEmpty())
        assertEquals(
            "постоянный код вручную не стирается",
            listOf("P0420"),
            vm.dtc.value.permanent.map { it.code }
        )
        shot("09_dtc_after_clear")

        // Автомобиль: VIN собран из многокадрового ответа и разобран.
        openTab("Авто")
        pumpUntil(20_000, "данные автомобиля") { vm.vehicle.value.loaded }
        val vehicle = vm.vehicle.value
        assertEquals("XTA210990G1234567", vehicle.vin)
        assertEquals("LADA (АвтоВАЗ)", vehicle.vinInfo?.manufacturer)
        assertEquals(2016, vehicle.vinInfo?.modelYear)
        assertEquals("ECM-EngineControl", vehicle.ecuName)
        assertNotNull("напряжение на разъёме", vehicle.adapterVoltage)
        compose.onNodeWithText("XTA210990G1234567").assertExists()
        shot("10_vehicle")

        // Поездка: запись, итоги, выгрузка CSV.
        openTab("Поездка")
        shot("11_trip_empty")
        compose.onNodeWithText("Начать запись").performClick()
        pumpUntil(30_000, "замеры поездки") { vm.trip.value.stats.sampleCount >= 3 }
        compose.onNodeWithText("Остановить запись").performClick()
        compose.waitForIdle()
        val stats = vm.trip.value.stats
        assertTrue("есть замеры: ${stats.sampleCount}", stats.sampleCount >= 3)
        assertTrue("обороты записались: ${stats.maxRpm}", stats.maxRpm > 300.0)
        shot("12_trip_stats")

        compose.onNodeWithText("Сохранить CSV").performScrollTo().performClick()
        pumpUntil(20_000, "выгрузка CSV") { vm.trip.value.exportedUri != null }
        shot("13_trip_exported")

        // Отключение возвращает экран связи в исходное состояние.
        openTab("Связь")
        compose.onNodeWithText("Отключиться").performScrollTo().performClick()
        pumpUntil(10_000, "разрыв связи") { vm.connection.value is ConnectionState.Disconnected }
        shot("14_disconnected")
    }
}
