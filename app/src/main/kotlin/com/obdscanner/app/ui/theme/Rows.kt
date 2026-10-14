package com.obdscanner.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Значки для круглых плашек. Рисуются линиями, а не берутся картинками:
 * одинаковая толщина штриха на любом экране, никакого веса в APK и ничего
 * чужого.
 */
enum class RowGlyph {
    /** Галочка - всё в порядке. */
    OK,

    /** Восклицательный знак - есть на что посмотреть. */
    ALERT,

    /** Вопрос - данных нет. */
    UNKNOWN,

    /** Список - раздел со строками. */
    LIST,

    /** Шкала - приборы и измерения. */
    GAUGE,

    /** Волна - датчик, который колеблется (лямбда-зонд). */
    WAVE,

    /** Столбики - счётчики. */
    BARS,
}

private fun DrawScope.glyph(kind: RowGlyph, color: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    when (kind) {
        RowGlyph.OK -> {
            drawLine(color, Offset(w * 0.26f, h * 0.52f), Offset(w * 0.44f, h * 0.70f), strokeWidth = stroke)
            drawLine(color, Offset(w * 0.44f, h * 0.70f), Offset(w * 0.76f, h * 0.32f), strokeWidth = stroke)
        }

        RowGlyph.ALERT -> {
            drawLine(color, Offset(w / 2, h * 0.26f), Offset(w / 2, h * 0.58f), strokeWidth = stroke)
            drawCircle(color, radius = stroke * 0.7f, center = Offset(w / 2, h * 0.72f))
        }

        RowGlyph.UNKNOWN -> {
            val arc = Path().apply {
                moveTo(w * 0.36f, h * 0.38f)
                cubicTo(w * 0.38f, h * 0.22f, w * 0.68f, h * 0.24f, w * 0.62f, h * 0.44f)
                cubicTo(w * 0.58f, h * 0.56f, w * 0.50f, h * 0.54f, w * 0.50f, h * 0.64f)
            }
            drawPath(arc, color, style = Stroke(stroke))
            drawCircle(color, radius = stroke * 0.7f, center = Offset(w * 0.50f, h * 0.76f))
        }

        RowGlyph.LIST -> {
            for (i in 0 until 3) {
                val y = h * (0.34f + i * 0.16f)
                drawCircle(color, radius = stroke * 0.6f, center = Offset(w * 0.30f, y))
                drawLine(color, Offset(w * 0.42f, y), Offset(w * 0.74f, y), strokeWidth = stroke)
            }
        }

        RowGlyph.GAUGE -> {
            val arc = Path().apply {
                moveTo(w * 0.24f, h * 0.68f)
                cubicTo(w * 0.24f, h * 0.26f, w * 0.76f, h * 0.26f, w * 0.76f, h * 0.68f)
            }
            drawPath(arc, color, style = Stroke(stroke))
            drawLine(color, Offset(w * 0.50f, h * 0.66f), Offset(w * 0.66f, h * 0.38f), strokeWidth = stroke)
        }

        RowGlyph.WAVE -> {
            val wave = Path().apply {
                moveTo(w * 0.22f, h * 0.50f)
                cubicTo(w * 0.34f, h * 0.18f, w * 0.44f, h * 0.82f, w * 0.56f, h * 0.50f)
                cubicTo(w * 0.66f, h * 0.24f, w * 0.72f, h * 0.62f, w * 0.80f, h * 0.50f)
            }
            drawPath(wave, color, style = Stroke(stroke))
        }

        RowGlyph.BARS -> {
            val bars = listOf(0.62f, 0.44f, 0.30f)
            bars.forEachIndexed { i, top ->
                val x = w * (0.32f + i * 0.18f)
                drawLine(color, Offset(x, h * 0.74f), Offset(x, h * top), strokeWidth = stroke * 1.4f)
            }
        }
    }
}

/**
 * Круглая цветная плашка со значком - "светофор" состояния раздела или теста.
 *
 * Цвет здесь несёт смысл, а не оформление: зелёный - можно не смотреть,
 * янтарный - стоит посмотреть, красный - смотреть обязательно. Поэтому она
 * стоит слева, до названия: взгляд на экран за рулём короткий, и первым должен
 * попадаться цвет, а не текст.
 */
@Composable
fun StatusCircle(color: Color, glyph: RowGlyph, modifier: Modifier = Modifier, diameter: Int = 40) {
    Box(
        modifier
            .size(diameter.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size((diameter * 0.74f).dp)) {
            glyph(glyph, Color.White, stroke = size.width * 0.11f)
        }
    }
}

/** Шеврон "дальше" - рисуется двумя линиями, чтобы не тащить иконочный шрифт. */
@Composable
private fun Chevron(modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val stroke = size.width * 0.14f
        drawLine(
            TextDim,
            Offset(size.width * 0.38f, size.height * 0.28f),
            Offset(size.width * 0.64f, size.height * 0.50f),
            strokeWidth = stroke
        )
        drawLine(
            TextDim,
            Offset(size.width * 0.64f, size.height * 0.50f),
            Offset(size.width * 0.38f, size.height * 0.72f),
            strokeWidth = stroke
        )
    }
}

/**
 * Пункт меню раздела: круглый значок, название, описание и короткое состояние.
 *
 * Описание под названием - не украшение. «Результаты тестов» ничего не говорит
 * человеку, который не знает, что это режим 06; строка «насколько близко узел
 * к порогу ошибки» говорит. А состояние справа («3 кода», «1 вне пределов»)
 * позволяет не заходить в раздел, чтобы узнать, есть ли там что-то.
 */
@Composable
fun MenuRow(
    title: String,
    description: String,
    glyph: RowGlyph,
    circleColor: Color,
    state: String? = null,
    stateColor: Color = TextMuted,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusCircle(if (enabled) circleColor else CircleSlate, glyph)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) TextBright else TextDim,
                fontWeight = FontWeight.Medium
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = uiScale().hint),
                color = TextMuted
            )
        }
        if (state != null) {
            Text(
                state,
                style = MaterialTheme.typography.bodyMedium,
                color = stateColor,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End
            )
        }
        Chevron()
    }
}

/**
 * Строка результата теста: название с описанием сверху, а под ними четыре
 * колонки - минимум, значение, максимум и единица.
 *
 * Такая раскладка взята не из любви к таблицам: у режима 06 смысл не в
 * значении, а в его расстоянии до предела, и пока минимум, значение и максимум
 * стоят в одной строке друг под другом, это видно без арифметики в голове.
 */
@Composable
fun MeasureRow(
    title: String,
    description: String,
    glyph: RowGlyph,
    circleColor: Color,
    min: String?,
    value: String,
    max: String?,
    unit: String,
    valueColor: Color = TextBright,
    position: Double? = null,
    /** Подписи колонок - приходят из ресурсов, чтобы раскладка не знала языка. */
    labels: Triple<String, String, String>
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCircle(circleColor, glyph, diameter = 34)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = TextBright)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = uiScale().hint),
                    color = TextMuted
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 46.dp)) {
            MeasureCell(labels.first, min ?: "—", Modifier.weight(1f), TextMuted)
            MeasureCell(labels.second, value, Modifier.weight(1f), valueColor, bold = true)
            MeasureCell(labels.third, max ?: "—", Modifier.weight(1f), TextMuted)
            MeasureCell("", unit.ifEmpty { "—" }, Modifier.weight(0.8f), TextMuted)
        }
        if (position != null) {
            LimitBar(position, valueColor, Modifier.padding(top = 6.dp, start = 46.dp))
        }
    }
}

@Composable
private fun MeasureCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color,
    bold: Boolean = false
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = uiScale().hint),
            color = TextDim
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * Полоска "где значение между пределами". Это и есть ответ на вопрос "сколько
 * осталось": число 42 при пределе 36 ничего не говорит человеку, который не
 * знает, что это за величина, а полоска, упёршаяся в край, говорит.
 */
@Composable
fun LimitBar(position: Double, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(PanelBorder)
    ) {
        Box(
            Modifier
                .fillMaxWidth(position.coerceIn(0.0, 1.0).toFloat())
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

/** Строка "параметр - значение - единица" с крупным значением акцентом. */
@Composable
fun ValueRow(title: String, value: String, unit: String = "", valueColor: Color = GaugeCyan) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
        if (unit.isNotEmpty()) {
            Text(
                " $unit",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim
            )
        }
    }
}
