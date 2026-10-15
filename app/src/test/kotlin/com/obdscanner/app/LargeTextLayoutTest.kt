package com.obdscanner.app

import android.os.Looper
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
 * Приложение с самым крупным шрифтом.
 *
 * Размер текста увеличивают тогда, когда плохо видно, - то есть именно тогда,
 * когда интерфейс обязан работать лучше всего. А ломается он как раз на этом:
 * в строке "название - значение" длинное название забирает всю ширину, и
 * значение переносится по одной букве ("не поддержива / ется", "1 , 6 %").
 *
 * Тест ставит масштаб на максимум, проходит по экранам и снимает их, а заодно
 * проверяет числом то, что на снимке видно глазом: значение не сжато в узкий
 * столбик.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-rRU-w360dp-h800dp-xhdpi")
class LargeTextLayoutTest {

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
    fun biggestTextStillFits() {
        val vm = ViewModelProvider(compose.activity)[ObdViewModel::class.java]
        // Самый крупный шрифт, какой разрешает настройка.
        vm.setTextScale(2.0f)
        compose.waitForIdle()

        shot("39_large_text_connect")
        compose.onNodeWithText("Демо").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Подключиться").performScrollTo().performClick()
        compose.waitForIdle()
        shot("39b_large_text_connecting")
        pumpUntil(30_000, "связь: ${vm.connection.value}") {
            vm.connection.value is ConnectionState.Connected
        }

        compose.onAllNodesWithText("Проверка").onLast().performClick()
        compose.waitForIdle()
        shot("40_large_text_menu")

        // Ошибки и готовность бортовых тестов: там самые длинные значения
        // ("не поддерживается") и самые длинные названия.
        compose.onAllNodesWithText("Ошибки").onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Считать ошибки").performScrollTo().performClick()
        pumpUntil(30_000, "чтение кодов") { vm.dtc.value.loaded }
        compose.waitForIdle()

        shot("41_large_text_codes")

        // Полная проверка: самый плотный экран приложения.
        compose.onAllNodesWithText("Проверка").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("К разделам").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Полная проверка").onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Проверить всё").performScrollTo().performClick()
        pumpUntil(60_000, "полная проверка") {
            !vm.quickCheck.value.running && vm.quickCheck.value.report != null
        }
        compose.waitForIdle()
        shot("42_large_text_report")
        assertTrue("отчёт собрался", vm.quickCheck.value.report!!.dtcTotal > 0)

        // Вот это и есть проверка словами теста: длинное значение занимает
        // нормальную ширину, а не четверть экрана, в которой оно рассыпается
        // по буквам. "не поддерживается" - самое длинное значение в отчёте.
        val value = compose.onAllNodesWithText("не поддерживается").onFirst()
        value.performScrollTo()
        value.assertWidthIsAtLeast(110.dp)
        shot("43_large_text_readiness")

        // Результаты режима 06: при обычном шрифте это четыре колонки
        // (минимум, значение, максимум, единица), при крупном - строки.
        // На колонку иначе остаётся четверть ширины телефона, и числа
        // начинают переноситься по цифрам.
        compose.onAllNodesWithText("Проверка").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("К разделам").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Результаты тестов").onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Прочитать").performScrollTo().performClick()
        pumpUntil(30_000, "чтение режима 06") { vm.tests.value.loaded && !vm.tests.value.running }
        compose.waitForIdle()
        // "значение" - подпись строки в той самой раскладке для крупного
        // шрифта: если она есть, значит включилась она, а не четыре колонки.
        compose.onAllNodesWithText("значение").onFirst().performScrollTo()
        shot("44_large_text_mode06")
    }
}
