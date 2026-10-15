package com.obdscanner.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Строка "название - значение", которая не рассыпается при крупном шрифте.
 *
 * Обычный `Row` с двумя текстами здесь не годится, и это видно только на
 * настоящем телефоне с увеличенным шрифтом: длинное название забирает всю
 * ширину, значению остаётся пара символов, и оно переносится по буквам -
 * "не поддержива / ется", "1 , 6 %". Размер шрифта в машине увеличивают
 * именно тогда, когда плохо видно, то есть ломается оно ровно в тот момент,
 * когда должно работать лучше всего.
 *
 * Поэтому ширину решает замер, а не пропорции: если название и значение
 * помещаются в строку - они стоят в строку, значение прижато к правому краю.
 * Если нет - значение уходит на следующую строку целиком, под названием. Это
 * тот же приём, которым верстают счета и спецификации, и по той же причине:
 * значение важнее выравнивания.
 */
@Composable
fun LabelValue(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = 12.dp,
    /** Отступ между строками, когда значение ушло вниз. */
    stackedGap: Dp = 2.dp
) {
    Layout(contents = listOf(label, value), modifier = modifier) { (labelContent, valueContent), constraints ->
        val labelMeasurable = labelContent.firstOrNull()
        val valueMeasurable = valueContent.firstOrNull()
        if (labelMeasurable == null || valueMeasurable == null) {
            return@Layout layout(0, 0) {}
        }

        val gapPx = gap.roundToPx()
        val stackedGapPx = stackedGap.roundToPx()
        val available = constraints.maxWidth

        // Сколько места текст попросил бы, если бы его никто не ограничивал.
        val labelWanted = labelMeasurable.maxIntrinsicWidth(constraints.maxHeight)
        val valueWanted = valueMeasurable.maxIntrinsicWidth(constraints.maxHeight)

        val fits = available == Constraints.Infinity || labelWanted + gapPx + valueWanted <= available
        if (fits) {
            val width = if (available == Constraints.Infinity) labelWanted + gapPx + valueWanted else available
            val valuePlaceable = valueMeasurable.measure(Constraints(maxWidth = valueWanted))
            val labelPlaceable = labelMeasurable.measure(
                Constraints(maxWidth = (width - gapPx - valuePlaceable.width).coerceAtLeast(0))
            )
            val height = max(labelPlaceable.height, valuePlaceable.height)
            return@Layout layout(width, height) {
                labelPlaceable.place(0, (height - labelPlaceable.height) / 2)
                valuePlaceable.place(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
            }
        }

        // Не помещается - значение целиком уходит под название, вправо.
        val labelPlaceable = labelMeasurable.measure(Constraints(maxWidth = available))
        val valuePlaceable = valueMeasurable.measure(Constraints(maxWidth = available))
        val height = labelPlaceable.height + stackedGapPx + valuePlaceable.height
        layout(available, height) {
            labelPlaceable.place(0, 0)
            valuePlaceable.place(available - valuePlaceable.width, labelPlaceable.height + stackedGapPx)
        }
    }
}

/** Пустая ячейка - чтобы раскладка не знала о том, что значения может не быть. */
@Composable
fun EmptyCell() {
    Box(Modifier)
}
