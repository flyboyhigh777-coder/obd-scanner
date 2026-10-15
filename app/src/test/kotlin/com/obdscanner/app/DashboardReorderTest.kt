package com.obdscanner.app

import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.obdscanner.app.ui.TileSize
import com.obdscanner.core.obd.Pids
import org.junit.Assert.assertEquals
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
 * Перестановка плиток приборки пальцем.
 *
 * Арифметика перестановки проверена отдельно ([TileReorderTest]), а здесь -
 * то, что к ней ведёт: долгое нажатие действительно берёт плитку, перетаскивание
 * доводит её до соседней ячейки, а новый порядок переживает перезапуск, потому
 * что лёг в настройки.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class DashboardReorderTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val outDir = File("build/outputs/roborazzi").apply { mkdirs() }

    /**
     * Снимок со всеми окнами сразу - меню выбора размера живёт в своём окне
     * поверх экрана, и обычный снимок корня его не захватывает.
     */
    private fun screenShot(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage(File(outDir, "$name.png").path)
    }

    private fun pumpUntil(timeoutMillis: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("Не дождались: $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun longPressDragSwapsTiles() {
        val vm = ViewModelProvider(compose.activity)[ObdViewModel::class.java]

        compose.onNodeWithText("Демо").performClick()
        compose.onNodeWithText("Подключиться").performClick()
        pumpUntil(30_000, "связь") { vm.connection.value is ConnectionState.Connected }

        compose.onAllNodesWithText("Приборы").onLast().performClick()
        pumpUntil(20_000, "первые данные") { vm.live.value.hasData }
        shot("50_reorder_before")

        // Первые две плитки (большие шкалы оборотов и скорости не в счёт):
        // температура охлаждающей жидкости и нагрузка на двигатель.
        val before = vm.dashboardPids.value
        val coolant = Pids.COOLANT_TEMP.id
        val load = Pids.ENGINE_LOAD.id
        assertTrue(
            "температура стоит перед нагрузкой: $before",
            before.indexOf(coolant) < before.indexOf(load)
        )

        // Долгое нажатие и перетаскивание вправо на соседнюю ячейку.
        compose.onNodeWithText("Температура ОЖ").performTouchInput {
            down(center)
            // Дольше, чем системный порог долгого нажатия, иначе это прокрутка.
            advanceEventTime(900)
            moveTo(center + Offset(600f, 0f))
            advanceEventTime(120)
            moveTo(center + Offset(620f, 0f))
            advanceEventTime(120)
            up()
        }
        compose.waitForIdle()
        shot("51_reorder_after")

        val after = vm.dashboardPids.value
        assertTrue(
            "температура переехала за нагрузку: $after",
            after.indexOf(coolant) > after.indexOf(load)
        )
        // Набор плиток при этом тот же - плитка переставлена, а не потеряна.
        assertEquals(before.sorted(), after.sorted())
        // И порядок сохранён: при следующем запуске он будет таким же.
        assertEquals(after, vm.settings.dashboardPids)

        // Вторая перестановка подряд - отдельный случай, и он ломался:
        // обработчик жеста запускается один раз и держит тот порядок плиток,
        // что был при запуске. Пока перетаскивание одно, это незаметно.
        // Теперь на экране [нагрузка, температура, дроссель, ...]; тянем
        // температуру на ряд вниз, на место температуры воздуха.
        val throttle = Pids.THROTTLE.id
        val intake = Pids.INTAKE_TEMP.id
        assertEquals(
            "перед второй перестановкой порядок известен: $after",
            listOf(load, coolant, throttle, intake),
            after.filter { it == load || it == coolant || it == throttle || it == intake }
        )
        compose.onNodeWithText("Температура ОЖ").performTouchInput {
            down(center)
            advanceEventTime(900)
            moveTo(center + Offset(0f, 420f))
            advanceEventTime(120)
            moveTo(center + Offset(0f, 440f))
            advanceEventTime(120)
            up()
        }
        compose.waitForIdle()
        shot("52_reorder_twice")

        val twice = vm.dashboardPids.value
        // Поехать должна температура - та, что под пальцем. По старому
        // порядку на её месте стояла нагрузка, и уезжала бы она.
        assertTrue(
            "температура ушла за температуру воздуха: $twice",
            twice.indexOf(coolant) > twice.indexOf(intake)
        )
        assertTrue(
            "нагрузка осталась впереди дросселя: $twice",
            twice.indexOf(load) < twice.indexOf(throttle)
        )
        assertEquals(before.sorted(), twice.sorted())

        // Касание после перестановок открывает размер той плитки, которой
        // коснулись, а не той, что стояла на этом месте вначале.
        compose.onNodeWithText("Нагрузка на двигатель").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Во всю ширину").performClick()
        compose.waitForIdle()
        assertEquals(TileSize.FULL, vm.dashboardTileSizes.value[load])
        assertEquals(null, vm.dashboardTileSizes.value[coolant])
    }

    @Test
    fun tapPicksTileSize() {
        val vm = ViewModelProvider(compose.activity)[ObdViewModel::class.java]

        compose.onNodeWithText("Демо").performClick()
        compose.onNodeWithText("Подключиться").performClick()
        pumpUntil(30_000, "связь") { vm.connection.value is ConnectionState.Connected }

        compose.onAllNodesWithText("Приборы").onLast().performClick()
        pumpUntil(20_000, "первые данные") { vm.live.value.hasData }

        val coolant = Pids.COOLANT_TEMP.id
        assertEquals(TileSize.NORMAL, vm.dashboardTileSizes.value[coolant] ?: TileSize.NORMAL)

        // Короткое касание плитки - выбор её размера там же, где она лежит.
        compose.onNodeWithText("Температура ОЖ").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Размер плитки").assertExists()
        // На телефоне выбор из двух: "пошире" и "во всю ширину" в две колонки
        // это одно и то же.
        compose.onAllNodesWithText("Пошире").assertCountEquals(0)
        screenShot("52_tile_size_menu")

        compose.onNodeWithText("Во всю ширину").performClick()
        compose.waitForIdle()
        shot("53_tile_size_full")

        assertEquals(TileSize.FULL, vm.dashboardTileSizes.value[coolant])
        // И размер переживёт перезапуск - он лёг в настройки.
        assertEquals(TileSize.FULL.code, vm.settings.dashboardTileSizes[coolant])

        // Обратно - и запись из настроек убирается, а не остаётся "обычной".
        compose.onNodeWithText("Температура ОЖ").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Обычная").performClick()
        compose.waitForIdle()
        assertEquals(null, vm.dashboardTileSizes.value[coolant])
        assertEquals(null, vm.settings.dashboardTileSizes[coolant])
    }
}
