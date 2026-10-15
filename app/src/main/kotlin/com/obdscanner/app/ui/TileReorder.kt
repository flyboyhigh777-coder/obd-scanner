package com.obdscanner.app.ui

import kotlin.math.roundToInt

/**
 * Арифметика перестановки плиток перетаскиванием.
 *
 * Вынесена из экрана отдельно и нарочно без единого обращения к Compose:
 * ошибиться здесь легко (в знаке, в округлении, на краях сетки), а поймать
 * ошибку на телефоне пальцем - долго. Здесь это обычные числа, которые
 * проверяются обычными тестами.
 */
object TileReorder {

    /**
     * Куда попадёт плитка, если отпустить палец сейчас.
     *
     * Считается не от абсолютных координат, а от сдвига в клетках: плитка
     * переезжает в соседнюю ячейку, когда палец ушёл больше чем на половину
     * её ширины. Иначе место менялось бы от дрожания руки в машине на ходу.
     *
     * @param from откуда тянут.
     * @param offsetX сдвиг пальца от начала перетаскивания, пикселей.
     * @param cellWidth ширина плитки вместе с промежутком до соседней.
     * @param columns сколько плиток в ряду.
     * @param count сколько плиток всего.
     */
    fun targetIndex(
        from: Int,
        offsetX: Float,
        offsetY: Float,
        cellWidth: Float,
        cellHeight: Float,
        columns: Int,
        count: Int
    ): Int {
        if (count <= 1 || columns <= 0) return from
        if (cellWidth <= 0f || cellHeight <= 0f) return from

        val column = from % columns
        val row = from / columns
        val targetColumn = (column + (offsetX / cellWidth).roundToInt()).coerceIn(0, columns - 1)
        val targetRow = (row + (offsetY / cellHeight).roundToInt()).coerceAtLeast(0)
        // Последний ряд бывает неполным: плитку, утянутую за его край, кладём
        // в конец, а не в пустоту.
        return (targetRow * columns + targetColumn).coerceIn(0, count - 1)
    }

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
