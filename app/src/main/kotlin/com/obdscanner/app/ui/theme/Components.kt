package com.obdscanner.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.unit.dp

/** Заголовок экрана на градиентной "таблетке" - как подсветка приборов. */
@Composable
fun GradientTopBar(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0F5C66), Color(0xFF1B3A8C))))
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
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PanelFill)
            .border(1.dp, PanelBorder, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
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
    Text(text, style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = modifier)
}

/** Строка "название - значение". */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = TextBright) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

/**
 * Кнопка-сегмент: выбранный вариант - светлая заливка, остальные - обводка.
 * Пара Button/OutlinedButton вместо FilterChip сознательно: чипы Material 3 в
 * тёмной схеме дают слишком слабый контраст для чтения на ходу.
 */
@Composable
fun SegmentButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = SegmentSelected,
                contentColor = SegmentSelectedText
            )
        ) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(text, color = TextBright)
        }
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
