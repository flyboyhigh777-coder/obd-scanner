package com.obdscanner.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Заголовок экрана на градиентной "таблетке" - как подсветка приборов. */
@Composable
fun GradientTopBar(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(HeaderStart, HeaderEnd)))
            .padding(vertical = 14.dp, horizontal = 18.dp)
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = TextBright)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextBright.copy(alpha = 0.85f))
            }
        }
    }
}

/** Карточка-панель: полупрозрачная заливка и тонкая обводка поверх графита. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // На магнитоле отступы меньше: место нужно цифрам, а не воздуху.
    val scale = uiScale()
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PanelFill)
            .border(1.dp, PanelBorder, RoundedCornerShape(18.dp))
            .padding(scale.panelPadding)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(scale.panelGap), content = content)
    }
}

/** Заголовок раздела внутри панели. */
@Composable
fun PanelTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = TextBright, fontWeight = FontWeight.SemiBold)
}

/** Пояснение мелким серым - то, что объясняет, зачем раздел нужен. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    // На широком экране магнитолы bodySmall (12 sp) читается с трудом: её
    // смотрят с вытянутой руки, а не держат перед лицом. Там же, где текста
    // много - расшифровки ошибок, подсказки, - это и есть то, что человек
    // приходит прочитать.
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = uiScale().hint),
        color = TextMuted,
        modifier = modifier
    )
}

/**
 * Строка "название - значение".
 *
 * Ширину делит [LabelValue], а не пропорции: при крупном шрифте длинное
 * название иначе забирает всю строку, и значение переносится по буквам.
 */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = TextBright) {
    LabelValue(
        label = {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        },
        value = {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End
            )
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    )
}

/**
 * Кнопка-сегмент: выбранный вариант - светлая заливка, остальные - обводка.
 * Пара Button/OutlinedButton вместо FilterChip сознательно: чипы Material 3 в
 * тёмной схеме дают слишком слабый контраст для чтения на ходу.
 */
@Composable
fun SegmentButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Подпись в одну строку и с ограниченным ростом: кнопки стоят в ряд и
    // делят ширину поровну, поэтому при двойном размере текста "Bluetooth"
    // переносится и обрезается о фиксированную высоту кнопки.
    val label = @Composable { color: Color ->
        Text(
            text,
            color = color,
            fontSize = cappedSp(14f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false
        )
    }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SegmentSelected,
                contentColor = SegmentSelectedText
            )
        ) { label(SegmentSelectedText) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        ) { label(TextBright) }
    }
}

/** Цветной индикатор-точка: лампа Check Engine, состояние связи. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color)
    )
}
