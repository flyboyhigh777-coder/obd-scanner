package com.obdscanner.app

import com.obdscanner.app.ui.TileReorder
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
}
