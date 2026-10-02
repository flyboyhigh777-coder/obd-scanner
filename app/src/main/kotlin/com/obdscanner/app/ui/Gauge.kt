package com.obdscanner.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeCyan
import com.obdscanner.app.ui.theme.GaugeRed
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted
import kotlin.math.cos
import kotlin.math.sin

/**
 * Круглая шкала с дугой: заполненная часть показывает значение, тонкая -
 * остаток, насечки дают понять, где находишься, без чтения цифр.
 *
 * Стрелки нет сознательно: на телефоне она либо тонкая и незаметная, либо
 * толстая и закрывает цифры. Дуга читается с одного взгляда, а точное значение
 * всё равно написано в центре.
 */
@Composable
fun ArcGauge(
    title: String,
    valueText: String,
    unitText: String,
    fraction: Float?,
    accent: Color = GaugeCyan,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1.25f)) {
                val stroke = 10.dp.toPx()
                val inset = stroke / 2 + 4.dp.toPx()
                val diameter = minOf(size.width, size.height) - inset * 2
                val topLeft = Offset((size.width - diameter) / 2, inset)
                val arcSize = Size(diameter, diameter)
                val start = 135f
                val sweep = 270f
                drawArc(
                    color = Color.White.copy(alpha = 0.10f),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (fraction != null) {
                    drawArc(
                        color = accent,
                        startAngle = start,
                        sweepAngle = sweep * fraction.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
                // Насечки: каждые 27 градусов, то есть десять интервалов шкалы.
                val centre = Offset(topLeft.x + diameter / 2, topLeft.y + diameter / 2)
                val outer = diameter / 2 - stroke
                val inner = outer - 5.dp.toPx()
                for (k in 0..10) {
                    val angle = Math.toRadians((start + sweep * k / 10f).toDouble())
                    val dx = cos(angle).toFloat()
                    val dy = sin(angle).toFloat()
                    drawLine(
                        color = Color.White.copy(alpha = 0.22f),
                        start = Offset(centre.x + inner * dx, centre.y + inner * dy),
                        end = Offset(centre.x + outer * dx, centre.y + outer * dy),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(valueText, style = MaterialTheme.typography.headlineMedium, color = TextBright)
                if (unitText.isNotEmpty()) {
                    Text(unitText, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Плитка со значением. Цвет значения меняется, когда величина уходит за
 * безопасный предел - это единственный способ заметить перегрев или просадку
 * напряжения, не разглядывая цифры.
 */
@Composable
fun ValueTile(
    title: String,
    valueText: String,
    modifier: Modifier = Modifier,
    alert: Alert = Alert.NONE
) {
    Panel(modifier) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 2)
        Text(
            valueText,
            style = MaterialTheme.typography.titleLarge,
            color = when (alert) {
                Alert.NONE -> TextBright
                Alert.WARNING -> GaugeAmber
                Alert.DANGER -> GaugeRed
            }
        )
    }
}

enum class Alert { NONE, WARNING, DANGER }

/**
 * Полоска вместо круглой шкалы: занимает меньше места по высоте, поэтому на
 * экран влезает больше параметров. Та же величина, тот же цвет - другой способ
 * показа, который кому-то читается быстрее.
 */
@Composable
fun BarGauge(
    title: String,
    valueText: String,
    fraction: Float?,
    accent: Color = GaugeCyan,
    modifier: Modifier = Modifier
) {
    Panel(modifier) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1)
        Text(valueText, style = MaterialTheme.typography.titleLarge, color = TextBright)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
        ) {
            val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(color = Color.White.copy(alpha = 0.12f), cornerRadius = radius)
            if (fraction != null && fraction > 0f) {
                drawRoundRect(
                    color = accent,
                    size = androidx.compose.ui.geometry.Size(size.width * fraction.coerceIn(0f, 1f), size.height),
                    cornerRadius = radius
                )
            }
        }
    }
}
