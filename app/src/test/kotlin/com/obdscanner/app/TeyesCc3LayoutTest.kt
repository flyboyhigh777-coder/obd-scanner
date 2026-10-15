package com.obdscanner.app

import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.obdscanner.app.ui.TileSize
import com.obdscanner.core.obd.Pids
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
 * Та самая магнитола, на которую приложение ставится: Teyes CC3 2K, 11
 * дюймов, 2000x1200.
 *
 * При плотности xhdpi это 1000 x 600 dp - вдвое шире телефона и ровно на
 * границе, где раскладка переключается на "магнитольную": навигация уходит
 * влево рейкой, плитки встают по три в ряд, цифры берутся крупные. Проверять
 * это глазами не на чем, поэтому проверяет тест и снимает экран.
 *
 * Высота 600 dp - тут важнее всего: на телефоне высоты хватает на всё, а на
 * магнитоле экран низкий и широкий, и любая лишняя строка выталкивает цифры
 * за край.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-rRU-w1000dp-h600dp-land-xhdpi")
class TeyesCc3LayoutTest {

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
                runCatching { shot("99_teyes_timeout") }
                throw AssertionError("Не дождались: $what ($timeoutMillis мс)")
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun appRunsOnTeyesCc3() {
        val vm = ViewModelProvider(compose.activity)[ObdViewModel::class.java]

        shot("30_teyes_connect")

        compose.onNodeWithText("Демо").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Подключиться").performClick()
        pumpUntil(30_000, "подключение к виртуальной машине") {
            vm.connection.value is ConnectionState.Connected
        }

        compose.onAllNodesWithText("Приборы").onLast().performClick()
        compose.waitForIdle()
        pumpUntil(30_000, "первые показания") { vm.live.value.values.isNotEmpty() }
        assertTrue("приборы заполнились: ${vm.live.value.values}", vm.live.value.values.isNotEmpty())
        // Расход - то, ради чего эта панель и появилась на видном месте.
        // Подпись зависит от того, едет машина или стоит, поэтому проверяем,
        // что есть хоть одна из двух.
        // substring: к подписи может добавиться пометка "(расчётный)", если
        // машина не отдаёт расход напрямую.
        val moving = compose.onAllNodesWithText("Расход в движении", substring = true)
            .fetchSemanticsNodes().size
        val idle = compose.onAllNodesWithText("Расход на холостых", substring = true)
            .fetchSemanticsNodes().size
        assertTrue("панель расхода на экране", moving + idle > 0)
        shot("31_teyes_dashboard")

        // Плитки разной ширины: на магнитоле колонок три, и "пошире" здесь -
        // не то же самое, что "во всю ширину". Ряд из широкой и обычной, а
        // под ним - растянутая на весь ряд: если бы раскладка ломалась на
        // смешанных ширинах, сломалась бы именно здесь.
        vm.setDashboardTileSize(Pids.COOLANT_TEMP.id, TileSize.WIDE)
        vm.setDashboardTileSize(Pids.ENGINE_LOAD.id, TileSize.FULL)
        compose.waitForIdle()
        // Экран магнитолы низкий: плитки лежат ниже сгиба, и без прокрутки на
        // снимке была бы одна шкала оборотов.
        compose.onNodeWithText("Нагрузка на двигатель").performScrollTo()
        shot("34_teyes_tile_sizes")
        compose.onNodeWithText("Температура ОЖ").assertExists()
        vm.setDashboardTileSize(Pids.COOLANT_TEMP.id, TileSize.NORMAL)
        vm.setDashboardTileSize(Pids.ENGINE_LOAD.id, TileSize.NORMAL)
        compose.waitForIdle()

        // Ошибки лежат внутри меню диагностики: сначала вкладка, потом пункт.
        compose.onAllNodesWithText("Проверка").onLast().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Ошибки").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Считать ошибки").performClick()
        pumpUntil(30_000, "чтение кодов") { vm.dtc.value.loaded }
        shot("32_teyes_dtc")

        // Поиск блоков вне OBD-II: на магнитоле этот раздел самый длинный, и
        // на низком экране он первым бы и поехал.
        // На экране магнитолы (600 dp в высоту) кнопка уезжает под нижний
        // край, и клик без прокрутки уходит мимо - ровно то, что сделает
        // человек пальцем.
        compose.onNodeWithText("Найти блоки").performScrollTo().performClick()
        pumpUntil(60_000, "перебор адресов блоков") { vm.modules.value.loaded }
        assertTrue(
            "виртуальные блоки найдены: ${vm.modules.value.found.map { it.hex }}",
            vm.modules.value.found.isNotEmpty()
        )
        shot("33_teyes_modules")
    }
}
