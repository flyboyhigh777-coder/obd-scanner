package com.obdscanner.app

import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ui.DraggableTileGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Раскладка сетки плиток - то, с чем нельзя ошибиться молча.
 *
 * Тест родился из поломки: плиткам задавалась одна высота на всех, и на
 * телефоне с нестандартным шрифтом значение в плитке обрезалось наполовину.
 * Column внутри панели раздаёт детям остаток высоты, и панель, которой задали
 * высоту хоть на пиксель меньше нужной, зажимает последнюю строку - то самое
 * значение, ради которого плитку и смотрят. На снимках с обычным шрифтом это
 * не видно: там расчётная высота совпадала с настоящей.
 *
 * Поэтому проверяется не картинка, а условие: по высоте плитку не зажимают
 * никогда.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class DraggableTileGridTest {

    @get:Rule
    val compose = createComposeRule()

    /** Высоты плиток, которые тест задаёт сам, - разные нарочно. */
    private val tileHeights = listOf(100, 140, 160, 120)

    @Test
    fun tilesAreNeverSqueezedByHeight() {
        val seen = mutableListOf<Constraints>()
        var gapPx = 0
        var gridHeight = 0

        compose.setContent {
            gapPx = with(LocalDensity.current) { GAP.roundToPx() }
            DraggableTileGrid(
                // Обычная, обычная, широкая на весь ряд, обычная - три ряда.
                spans = listOf(1, 1, 2, 1),
                columns = 2,
                gap = GAP,
                onMove = { _, _ -> },
                modifier = Modifier.testTag("grid")
            ) { index, _, _, _ ->
                Layout(content = {}) { _, constraints ->
                    seen += constraints
                    layout(constraints.maxWidth, tileHeights[index]) {}
                }
            }
        }
        compose.waitForIdle()
        gridHeight = compose.onNodeWithTag("grid").fetchSemanticsNode().size.height

        assertEquals("меряются все плитки", tileHeights.size, seen.size)
        seen.forEachIndexed { index, constraints ->
            // Ширина ограничена сеткой - из неё и складывается ряд.
            assertTrue("ширина плитки $index ограничена", constraints.hasBoundedWidth)
            assertTrue("ширина плитки $index не нулевая", constraints.maxWidth > 0)
            // А высота - нет: плитка берёт столько, сколько нужно её строкам.
            assertEquals("плитку $index не зажали снизу", 0, constraints.minHeight)
            assertEquals(
                "плитку $index не ограничили сверху",
                Constraints.Infinity,
                constraints.maxHeight
            )
        }

        // Высота ряда - по самой высокой плитке в этом ряду, а не на экране:
        // 140 (первый ряд), 160 (широкая), 120 (последняя) и два промежутка.
        assertEquals(140 + 160 + 120 + gapPx * 2, gridHeight)
    }

    private companion object {
        val GAP = 12.dp
    }
}
