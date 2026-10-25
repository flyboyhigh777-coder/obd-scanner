package com.obdscanner.app

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Журнал поездки от начала до конца.
 *
 * Самое важное здесь - то, ради чего журнал и писался: показания, собранные
 * на ходу, должны пережить разрыв связи и показаться человеку потом. Поэтому
 * тест ведёт себя как водитель: подключился, поехал (виртуальная машина
 * отвечает), заглушил - и пошёл смотреть, что было.
 *
 * Формат файла и разбор проверяются отдельно и без Android
 * ([com.obdscanner.core.TripLogTest]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class TripLogTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    context.getSharedPreferences("obd_scanner", Context.MODE_PRIVATE)
                        .edit().clear().commit()
                    // И журналы прошлых прогонов: список поездок должен быть
                    // пустым, иначе тест найдёт чужую поездку.
                    File(context.filesDir, "triplog").deleteRecursively()
                    base.evaluate()
                }
            }
        }
        .around(compose)

    private val outDir = File("build/outputs/roborazzi").apply { mkdirs() }

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
    fun tripIsRecordedAndCanBeReadAfterwards() {
        val vm = ViewModelProvider(compose.activity)[ObdViewModel::class.java]
        // Секунда вместо пяти: тест не может ехать двадцать минут.
        vm.setTripLogIntervalSeconds(1)

        compose.onNodeWithText("Демо").performClick()
        compose.onNodeWithText("Подключиться").performClick()
        pumpUntil(30_000, "связь") { vm.connection.value is ConnectionState.Connected }
        pumpUntil(20_000, "первые данные") { vm.live.value.hasData }

        // Поехали: ждём, пока в журнал ляжет несколько замеров.
        pumpUntil(30_000, "замеры в журнале") { vm.live.value.values.isNotEmpty() }
        Thread.sleep(3_500)
        pumpUntil(10_000, "ещё замеры") { vm.live.value.hasData }

        // Заглушили двигатель - связь кончилась, поездка закрылась.
        vm.disconnect()
        pumpUntil(20_000, "поездка в списке") { vm.tripLogs.value.isNotEmpty() }

        val trip = vm.tripLogs.value.first()
        assertTrue("запись закончена, итог записан", trip.finished)
        assertTrue("в поездке есть замеры", (trip.summary?.samples ?: 0) >= 3)

        // Смотрим её глазами человека: вкладка «Замеры», список поездок.
        compose.onAllNodesWithText("Замеры").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Журнал поездок").performScrollTo()
        shot("70_triplog_list")

        // Открываем поездку и смотрим, как менялся параметр.
        vm.openTripLog(trip)
        pumpUntil(10_000, "поездка открылась") { vm.openTripLog.value != null }
        compose.onNodeWithText("Что смотрим").performScrollTo()
        shot("71_triplog_detail")

        compose.onNodeWithText("Минимум").assertExists()
        compose.onNodeWithText("Максимум").assertExists()
        compose.onNodeWithText("К списку поездок").assertExists()

        val open = vm.openTripLog.value!!
        assertTrue("параметры прочитались", open.parameters.isNotEmpty())
        assertTrue("замеры прочитались", open.file.samples.size >= 3)
    }
}
