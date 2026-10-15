package com.obdscanner.app

import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
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
    }
}
