package com.obdscanner.app

import com.obdscanner.app.ui.TileReorder
import com.obdscanner.app.ui.TileSize
import com.obdscanner.app.ui.TileReorder.moved
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Перестановка плиток приборки перетаскиванием.
 *
 * Обычный JUnit без Robolectric: вся арифметика перетаскивания - это числа,
 * и проверять её на телефоне пальцем незачем.
 */
class TileReorderTest {

    private val cell = 200f

    private fun target(from: Int, dx: Float, dy: Float, columns: Int = 2, count: Int = 6) =
        TileReorder.targetIndex(from, dx, dy, cell, cell, columns, count)

    @Test
    /** без сдвига плитка остаётся на месте. */
    fun noDragKeepsPlace() {
        assertEquals(3, target(3, 0f, 0f))
    }

    @Test
    /** дрожание руки место не меняет: нужно уйти больше чем на полклетки. */
    fun smallShakeDoesNotMove() {
        assertEquals(3, target(3, 80f, 0f))
        assertEquals(3, target(3, -80f, 70f))
    }

    @Test
    /** сдвиг на клетку вправо и вниз переставляет в соседнюю ячейку. */
    fun movesByWholeCells() {
        // 0 -> вправо на колонку = 1
        assertEquals(1, target(0, cell, 0f))
        // 0 -> вниз на ряд = 2 (две колонки)
        assertEquals(2, target(0, 0f, cell))
        // 0 -> вправо и вниз = 3
        assertEquals(3, target(0, cell, cell))
    }

    @Test
    /** за край сетки плитка не уезжает. */
    fun staysInsideTheGrid() {
        // Влево за первую колонку.
        assertEquals(2, target(2, -cell * 3, 0f))
        // Вправо за последнюю.
        assertEquals(3, target(2, cell * 5, 0f))
        // Вниз за последний ряд - в конец списка, а не в пустоту.
        assertEquals(5, target(4, 0f, cell * 9))
        // Вверх выше первого ряда.
        assertEquals(0, target(4, 0f, -cell * 9))
    }

    @Test
    /** вставка, а не обмен местами: остальные сдвигаются. */
    fun insertsInsteadOfSwapping() {
        val tiles = listOf("A", "B", "C", "D")
        assertEquals(listOf("B", "C", "A", "D"), tiles.moved(0, 2))
        assertEquals(listOf("A", "D", "B", "C"), tiles.moved(3, 1))
        // Перестановка на то же место ничего не меняет.
        assertEquals(tiles, tiles.moved(2, 2))
        // Мусорные номера не роняют приложение.
        assertEquals(tiles, tiles.moved(-1, 2))
        assertEquals(tiles, tiles.moved(1, 9))
    }

    @Test
    /** на одной плитке переставлять нечего. */
    fun singleTileIsNotDraggable() {
        assertEquals(0, TileReorder.targetIndex(0, cell * 3, cell * 3, cell, cell, 2, 1))
    }

    @Test
    /** широкая плитка не режется между рядами, а переезжает целиком. */
    fun wideTileTakesTheWholeNextRow() {
        // Четыре колонки: обычная, широкая на две, обычная, обычная.
        val cells = TileReorder.pack(listOf(1, 2, 1, 1), columns = 4)
        assertEquals(TileReorder.Cell(column = 0, row = 0, span = 1), cells[0])
        assertEquals(TileReorder.Cell(column = 1, row = 0, span = 2), cells[1])
        assertEquals(TileReorder.Cell(column = 3, row = 0, span = 1), cells[2])
        assertEquals(TileReorder.Cell(column = 0, row = 1, span = 1), cells[3])
        assertEquals(2, TileReorder.rows(cells))

        // Широкая плитка не влезает в остаток ряда - уходит в следующий
        // целиком, а в конце первого остаётся пустое место.
        val tight = TileReorder.pack(listOf(1, 1, 2), columns = 3)
        assertEquals(TileReorder.Cell(column = 1, row = 0, span = 1), tight[1])
        assertEquals(TileReorder.Cell(column = 0, row = 1, span = 2), tight[2])
    }

    @Test
    /** плитка шире сетки занимает ряд, а не уезжает за край. */
    fun tooWideTileIsClampedToTheGrid() {
        val cells = TileReorder.pack(listOf(4, 1), columns = 2)
        assertEquals(TileReorder.Cell(column = 0, row = 0, span = 2), cells[0])
        assertEquals(TileReorder.Cell(column = 0, row = 1, span = 1), cells[1])
    }

    @Test
    /** при плитках разной ширины номер ячейки и номер плитки - разные вещи. */
    fun dragHitsTheRightTileAmongWideOnes() {
        // 4 колонки, ряд: [широкая 0-1][обычная 2][обычная 3], ниже - ещё две.
        val spans = listOf(2, 1, 1, 1, 1)
        fun drag(from: Int, dx: Float, dy: Float) =
            TileReorder.targetIndex(from, dx, dy, cell, cell, 4, spans)

        // Третья плитка (колонка 2) сдвинута на колонку влево - попадает в
        // широкую, которая занимает колонки 0 и 1.
        assertEquals(0, drag(1, -cell, 0f))
        // Она же на колонку вправо - на место четвёртой.
        assertEquals(2, drag(1, cell, 0f))
        // Широкую тянут вправо: её левый край упирается так, чтобы правый не
        // ушёл за сетку, - это колонка 2, то есть место третьей плитки.
        assertEquals(1, drag(0, cell * 9, 0f))
        // Вниз на ряд - в нижний ряд, где лежат четвёртая и пятая.
        assertEquals(3, drag(0, 0f, cell))
    }

    @Test
    /** размер плитки переводится в колонки по ширине экрана. */
    fun sizesTurnIntoColumns() {
        assertEquals(1, TileSize.NORMAL.span(2))
        assertEquals(2, TileSize.WIDE.span(4))
        // На телефоне "пошире" - это уже весь ряд, шире некуда.
        assertEquals(2, TileSize.WIDE.span(2))
        assertEquals(2, TileSize.FULL.span(2))
        assertEquals(4, TileSize.FULL.span(4))
        // Выбор из двух на телефоне и из трёх на магнитоле: предлагать
        // "пошире" там, где оно совпадает с "во всю ширину", незачем.
        assertEquals(listOf(TileSize.NORMAL, TileSize.FULL), TileSize.choices(2))
        assertEquals(
            listOf(TileSize.NORMAL, TileSize.WIDE, TileSize.FULL),
            TileSize.choices(4)
        )
        // Незнакомая запись в настройках - обычная плитка, а не падение.
        assertEquals(TileSize.NORMAL, TileSize.of(null))
        assertEquals(TileSize.NORMAL, TileSize.of(77))
        assertEquals(TileSize.FULL, TileSize.of(TileSize.FULL.code))
    }
}
