package com.obdscanner.app.ui

import kotlin.math.roundToInt

/**
 * Арифметика сетки плиток: где какая лежит и куда переедет под пальцем.
 *
 * Вынесена из экрана отдельно и нарочно без единого обращения к Compose:
 * ошибиться здесь легко (в знаке, в округлении, на краях сетки, на плитке
 * шире одной колонки), а поймать ошибку на телефоне пальцем - долго. Здесь
 * это обычные числа, которые проверяются обычными тестами.
 */
object TileReorder {

    /** Место плитки в сетке: с какой колонки начинается, в каком ряду и какой ширины. */
    data class Cell(val column: Int, val row: Int, val span: Int)

    /**
     * Разложить плитки по сетке слева направо и сверху вниз.
     *
     * Плитка, которая не помещается в остаток ряда, переезжает в следующий
     * целиком, а не режется пополам: половина значения на одном краю экрана и
     * половина на другом - не то, на что можно смотреть на ходу. Из-за этого в
     * конце ряда остаётся пустое место, и это честнее, чем разорванная плитка.
     */
    fun pack(spans: List<Int>, columns: Int): List<Cell> {
        if (columns <= 0) return spans.map { Cell(0, 0, 1) }
        var column = 0
        var row = 0
        return spans.map { raw ->
            val span = raw.coerceIn(1, columns)
            if (column + span > columns) {
                row++
                column = 0
            }
            val cell = Cell(column = column, row = row, span = span)
            column += span
            if (column >= columns) {
                row++
                column = 0
            }
            cell
        }
    }

    /** Сколько рядов занимает такая раскладка. */
    fun rows(cells: List<Cell>): Int = (cells.maxOfOrNull { it.row } ?: -1) + 1

    /**
     * Куда попадёт плитка, если отпустить палец сейчас.
     *
     * Считается не от абсолютных координат, а от сдвига в клетках: плитка
     * переезжает в соседнюю ячейку, когда палец ушёл больше чем на половину
     * её ширины. Иначе место менялось бы от дрожания руки в машине на ходу.
     *
     * Найденной точке соответствует последняя плитка, которая начинается не
     * правее её: при плитках разной ширины номер ячейки и номер плитки - разные
     * вещи, и считать одно другим значило бы промахиваться мимо широких.
     *
     * @param from откуда тянут.
     * @param offsetX сдвиг пальца от начала перетаскивания, пикселей.
     * @param cellWidth ширина одной колонки вместе с промежутком до соседней.
     * @param cellHeight высота ряда вместе с промежутком до следующего.
     * @param columns сколько колонок в сетке.
     * @param spans ширина каждой плитки в колонках.
     */
    fun targetIndex(
        from: Int,
        offsetX: Float,
        offsetY: Float,
        cellWidth: Float,
        cellHeight: Float,
        columns: Int,
        spans: List<Int>
    ): Int {
        if (spans.size <= 1 || columns <= 0) return from
        if (from !in spans.indices) return from
        if (cellWidth <= 0f || cellHeight <= 0f) return from

        val cells = pack(spans, columns)
        val here = cells[from]
        // За край сетки плитка не уезжает: широкая упирается в правый край
        // своей шириной, а не левым краем.
        val column = (here.column + (offsetX / cellWidth).roundToInt())
            .coerceIn(0, (columns - here.span).coerceAtLeast(0))
        val row = (here.row + (offsetY / cellHeight).roundToInt()).coerceAtLeast(0)

        val point = row * columns + column
        var target = 0
        cells.forEachIndexed { index, cell ->
            if (cell.row * columns + cell.column <= point) target = index
        }
        return target
    }

    /** То же для сетки из одинаковых плиток - когда ширины ещё никто не менял. */
    fun targetIndex(
        from: Int,
        offsetX: Float,
        offsetY: Float,
        cellWidth: Float,
        cellHeight: Float,
        columns: Int,
        count: Int
    ): Int = targetIndex(from, offsetX, offsetY, cellWidth, cellHeight, columns, List(count) { 1 })

    /**
     * Перестановка: элемент вынимается и вставляется на новое место, а не
     * меняется местами с соседом.
     *
     * Разница видна сразу, как только плиток больше двух: обмен местами
     * оставляет дыру там, откуда тянули, а вставка сдвигает остальные - и
     * порядок получается такой, какой человек и рисовал пальцем.
     */
    fun <T> List<T>.moved(from: Int, to: Int): List<T> {
        if (from == to || from !in indices || to !in indices) return this
        val out = toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }
}
