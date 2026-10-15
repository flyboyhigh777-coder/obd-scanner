package com.obdscanner.app.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * Сетка плиток, которые можно переставлять пальцем.
 *
 * Берётся плитка долгим нажатием, а не обычным: по приборке водят пальцем,
 * прокручивая её, и короткое касание обязано оставаться прокруткой. Долгое
 * нажатие - то же самое, чем переставляют значки на рабочем столе телефона,
 * так что объяснять его не нужно; в момент захвата телефон коротко вибрирует,
 * чтобы это было понятно, не приглядываясь.
 *
 * Плитка едет за пальцем, а ячейка, в которую она встанет, подсвечивается.
 * Соседи при этом не прыгают с места на место: на ходу от шевелящейся сетки
 * рябит в глазах, а нужно видеть, куда кладёшь.
 *
 * Своя раскладка, а не ряды: во-первых, так известен точный размер ячейки -
 * из него и считается, куда плитка переедет; во-вторых, все плитки оказываются
 * соседями в одной раскладке, и перетаскиваемую можно поднять **над**
 * остальными. В рядах она уезжала бы под плитки следующего ряда.
 */
@Composable
fun DraggableTileGrid(
    count: Int,
    columns: Int,
    gap: Dp,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    tile: @Composable (index: Int, dragged: Boolean, isTarget: Boolean) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val gapPx = with(LocalDensity.current) { gap.toPx() }

    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Размер ячейки вместе с промежутком - замеряется по первой плитке:
    // сетка равномерная, остальные такие же.
    var cellWidth by remember { mutableStateOf(0f) }
    var cellHeight by remember { mutableStateOf(0f) }

    fun targetOf(from: Int): Int = TileReorder.targetIndex(
        from = from,
        offsetX = offset.x,
        offsetY = offset.y,
        cellWidth = cellWidth,
        cellHeight = cellHeight,
        columns = columns,
        count = count
    )

    val dragging = dragIndex
    val target = dragging?.let { targetOf(it) }

    Layout(
        modifier = modifier,
        content = {
            repeat(count) { index ->
                val isDragged = dragging == index
                Box(
                    Modifier
                        .onSizeChanged {
                            if (index == 0) {
                                cellWidth = it.width + gapPx
                                cellHeight = it.height + gapPx
                            }
                        }
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
                                        onMove(from, to)
                                    }
                                },
                                onDragCancel = {
                                    dragIndex = null
                                    offset = Offset.Zero
                                }
                            )
                        }
                ) {
                    tile(index, isDragged, target == index && dragging != index)
                }
            }
        }
    ) { measurables, constraints ->
        if (measurables.isEmpty() || columns <= 0) return@Layout layout(0, 0) {}
        val gapRound = gap.roundToPx()
        val width = constraints.maxWidth
        val cell = ((width - gapRound * (columns - 1)) / columns).coerceAtLeast(1)
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(cell)) }
        val rowHeight = placeables.maxOf { it.height }
        val rows = (placeables.size + columns - 1) / columns
        val height = rows * rowHeight + (rows - 1).coerceAtLeast(0) * gapRound
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val column = index % columns
                val row = index / columns
                placeable.placeRelative(
                    x = column * (cell + gapRound),
                    y = row * (rowHeight + gapRound),
                    // Перетаскиваемая плитка рисуется поверх соседних.
                    zIndex = if (index == dragging) 1f else 0f
                )
            }
        }
    }
}
