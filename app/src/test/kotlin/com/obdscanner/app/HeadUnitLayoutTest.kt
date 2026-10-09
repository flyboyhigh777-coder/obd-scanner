package com.obdscanner.app

import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
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
 * То же приложение на экране головного устройства.
 *
 * Размер взят у типовой магнитолы Teyes на Android: 1280x720, альбомная
 * ориентация, плотность mdpi - то есть 1280 dp в ширину. Это втрое шире
 * телефона в портрете, и на такой ширине приложение обязано выглядеть иначе:
 * навигация уходит влево рейкой (полоса снизу съедала бы высоту, которой на
 * низком экране и так мало), а плитки приборов встают по четыре в ряд.
 *
 * Тест проверяет именно это и снимает экран - чтобы раскладку для магнитолы
 * можно было увидеть, не имея магнитолы.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-rRU-w1280dp-h720dp-land-mdpi")
class HeadUnitLayoutTest {

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
            if (System.currentTimeMillis() > deadline) {
                runCatching { shot("99_headunit_timeout") }
                throw AssertionError("Не дождались: $what ($timeoutMillis мс)")
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun wideScreenUsesRailAndFourColumns() {
        val vm = ViewModelProvider(compose.activity)[ObdViewModel::class.java]

        shot("20_headunit_connect")

        compose.onNodeWithText("Демо").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Подключиться").performClick()
        pumpUntil(30_000, "подключение к виртуальной машине") {
            vm.connection.value is ConnectionState.Connected
        }

        // Вкладки на месте - значит рейка отрисовалась, а не пропала вместе с
        // нижней полосой.
        compose.onAllNodesWithText("Приборы").onLast().performClick()
        compose.waitForIdle()
        pumpUntil(30_000, "первые показания") { vm.live.value.values.isNotEmpty() }
        assertTrue(
            "обороты пришли: ${vm.live.value.values}",
            vm.live.value.values.isNotEmpty()
        )
        shot("21_headunit_dashboard")

        // Ошибки на широком экране - самый плотный текст, на нём видно, не
        // разъезжается ли вёрстка.
        compose.onAllNodesWithText("Ошибки").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Считать ошибки").performClick()
        pumpUntil(30_000, "чтение кодов") { vm.dtc.value.loaded }
        shot("22_headunit_dtc")

        compose.onAllNodesWithText("Авто").onLast().performClick()
        compose.waitForIdle()
        shot("23_headunit_vehicle")
    }
}
