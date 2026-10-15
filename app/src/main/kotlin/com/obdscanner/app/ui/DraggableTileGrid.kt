package com.obdscanner.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Сетка плиток, которые можно переставлять пальцем и растягивать.
 *
 * Берётся плитка долгим нажатием, а не обычным: по приборке водят пальцем,
 * прокручивая её, и короткое касание обязано оставаться прокруткой. Долгое
 * нажатие - то же самое, чем переставляют значки на рабочем столе телефона,
 * так что объяснять его не нужно; в момент захвата телефон коротко вибрирует,
 * чтобы это было понятно, не приглядываясь.
 *
 * Короткое касание освободилось - им и выбирается размер плитки. Настройка
 * размера живёт там же, где сама плитка, а не в отдельном списке галочек:
 * решение "эту хочу видеть крупно" принимают, глядя на приборку, а не на
 * список названий.
 *
 * Плитка едет за пальцем, а ячейка, в которую она встанет, подсвечивается.
 * Соседи при этом не прыгают с места на место: на ходу от шевелящейся сетки
 * рябит в глазах, а нужно видеть, куда кладёшь.
 *
 * Своя раскладка, а не ряды: во-первых, так известен точный размер ячейки -
 * из него и считается, куда плитка переедет; во-вторых, все плитки оказываются
 * соседями в одной раскладке, и перетаскиваемую можно поднять **над**
 * остальными. В рядах она уезжала бы под плитки следующего ряда. В-третьих,
 * плитка шире одной колонки в обычный ряд просто не укладывается.
 *
 * @param spans ширина каждой плитки в колонках.
 * @param onMove плитку перенесли на другое место.
 * @param onTap по плитке коротко нажали - показать выбор размера.
 */
@Composable
fun DraggableTileGrid(
    spans: List<Int>,
    columns: Int,
    gap: Dp,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    onTap: (index: Int) -> Unit = {},
    tile: @Composable (index: Int, span: Int, dragged: Boolean, isTarget: Boolean) -> Unit
) {
    val count = spans.size
    val haptics = LocalHapticFeedback.current

    // Жесты живут в корутине, которая запускается один раз на свои ключи и
    // держит те значения, что были при запуске. Ключи тут - номер плитки,
    // их число и число колонок; порядок плиток и их ширины в ключи не
    // годятся (от них зависит сам жест, и перезапускать его посреди
    // перетаскивания нельзя). Поэтому всё, что меняется под рукой, жест
    // берёт через rememberUpdatedState - иначе вторая перестановка подряд
    // двигала бы плитки по первому, давно устаревшему порядку.
    val latestSpans by rememberUpdatedState(spans)
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnTap by rememberUpdatedState(onTap)

    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Ширина одной колонки и высота ряда вместе с промежутками - из них
    // считается, куда плитка переедет. Замеряются по готовой раскладке, а не
    // вычисляются отдельно: так в счёт идёт та же ширина, что на экране.
    var cellWidth by remember { mutableStateOf(0f) }
    var cellHeight by remember { mutableStateOf(0f) }

    val cells = remember(spans, columns) { TileReorder.pack(spans, columns) }

    fun targetOf(from: Int): Int = TileReorder.targetIndex(
        from = from,
        offsetX = offset.x,
        offsetY = offset.y,
        cellWidth = cellWidth,
        cellHeight = cellHeight,
        columns = columns,
        spans = latestSpans
    )

    val dragging = dragIndex
    val target = dragging?.let { targetOf(it) }

    Layout(
        modifier = modifier,
        content = {
            repeat(count) { index ->
                val isDragged = dragging == index
                val span = cells.getOrNull(index)?.span ?: 1
                Box(
                    Modifier
                        .graphicsLayer {
                            if (isDragged) {
                                translationX = offset.x
                                translationY = offset.y
                                // Чуть крупнее и с тенью - видно, что плитка
                                // "поднята" и едет поверх остальных.
                                scaleX = 1.04f
                                scaleY = 1.04f
                                shadowElevation = 18f
                            }
                        }
                        .pointerInput(index, count, columns) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragIndex = index
                                    offset = Offset.Zero
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    offset += amount
                                },
                                onDragEnd = {
                                    val from = dragIndex
                                    val to = from?.let { targetOf(it) }
                                    dragIndex = null
                                    offset = Offset.Zero
                                    if (from != null && to != null && from != to) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        latestOnMove(from, to)
                                    }
                                },
                                onDragCancel = {
                                    dragIndex = null
                                    offset = Offset.Zero
                                }
                            )
                        }
                        .pointerInput(index) {
                            // Своё короткое касание вместо detectTapGestures.
                            // Готовое с обработчиком долгого нажатия съедает
                            // всё до отпускания пальца - вместе с
                            // перетаскиванием, которое после того же долгого
                            // нажатия и начинается; а без обработчика оно
                            // ждёт отпускания сколько угодно, и плитка,
                            // которую подержали и положили обратно, не сдвинув
                            // пальца, открывала меню размера.
                            //
                            // Здесь касание живёт ровно до порога долгого
                            // нажатия: не успел отпустить - это уже не
                            // касание, а захват плитки, и меню не при чём.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    waitForUpOrCancellation()
                                }
                                if (up != null) latestOnTap(index)
                            }
                        }
                ) {
                    tile(index, span, isDragged, target == index && dragging != index)
                }
            }
        }
    ) { measurables, constraints ->
        if (measurables.isEmpty() || columns <= 0) return@Layout layout(0, 0) {}
        val gapRound = gap.roundToPx()
        val width = constraints.maxWidth
        val column = ((width - gapRound * (columns - 1)) / columns).coerceAtLeast(1)
        fun widthOf(span: Int) = column * span + gapRound * (span - 1)

        // Плитка меряется только по ширине. Высоту ей задавать нельзя: Column
        // внутри панели раздаёт детям остаток высоты, и стоит ошибиться на
        // пиксель - значение, ради которого плитка и нужна, обрежется
        // наполовину. Ошибиться же легко: у шрифтов с высокими выносными
        // строка занимает больше, чем обещает расчёт.
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixedWidth(widthOf(cells.getOrNull(index)?.span ?: 1)))
        }
        // Высота ряда - по самой высокой плитке в нём, а не по самой высокой
        // на экране: в ряду из обычных плиток лишняя высота обернулась бы
        // двойным промежутком между рядами.
        val rows = TileReorder.rows(cells.take(placeables.size))
        val rowHeights = IntArray(rows)
        placeables.forEachIndexed { index, placeable ->
            val row = cells.getOrNull(index)?.row ?: return@forEachIndexed
            if (placeable.height > rowHeights[row]) rowHeights[row] = placeable.height
        }
        val rowTops = IntArray(rows)
        var top = 0
        for (row in 0 until rows) {
            rowTops[row] = top
            top += rowHeights[row] + gapRound
        }
        val height = (top - gapRound).coerceAtLeast(0)
        cellWidth = (column + gapRound).toFloat()
        // Для перетаскивания ряды считаются одинаковыми - средним по сетке.
        // Разница между ними это несколько пикселей от более крупных цифр в
        // широкой плитке, а палец и так метит в ячейку, которая подсвечена.
        cellHeight = if (rows > 0) (height + gapRound).toFloat() / rows else 0f
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val cell = cells.getOrNull(index) ?: return@forEachIndexed
                placeable.placeRelative(
                    x = cell.column * (column + gapRound),
                    y = rowTops.getOrElse(cell.row) { 0 },
                    // Перетаскиваемая плитка рисуется поверх соседних.
                    zIndex = if (index == dragging) 1f else 0f
                )
            }
        }
    }
}
