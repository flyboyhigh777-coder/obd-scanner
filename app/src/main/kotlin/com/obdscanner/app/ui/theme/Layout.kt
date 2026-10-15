package com.obdscanner.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ширина экрана - от неё зависит и навигация, и число колонок.
 *
 * Приложение ставят не только на телефон. Головное устройство в машине
 * (Teyes и подобные на Android) - это всегда альбомная ориентация и экран,
 * широкий и низкий: 1024x600 у простых, 1280x720 у десятидюймовых, до
 * 1920x1200 у 2K. Телефонная раскладка там работает, но плохо: узкая колонка
 * посередине, половина ширины пустая, а по высоте всё время нужна прокрутка -
 * и это на экране, в который смотрят за рулём.
 *
 * Поэтому разделение простое и по фактической ширине, а не по типу устройства:
 * планшет, магнитола и телефон в альбомной ориентации ведут себя одинаково,
 * потому что задача у них одна и та же.
 */
enum class ScreenWidth {
    /** Телефон в портретной ориентации: одна колонка, навигация снизу. */
    COMPACT,

    /** Планшет, магнитола, телефон боком: навигация сбоку, несколько колонок. */
    WIDE,
}

/**
 * Порог в 600 dp - обычная граница между телефоном и планшетом в Android.
 * Телефон в портрете это 360-430 dp, в альбомной - уже 700-900; магнитола
 * 1024 dp и больше.
 */
const val WIDE_SCREEN_DP = 600

@Composable
@ReadOnlyComposable
fun screenWidth(): ScreenWidth =
    if (LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP) ScreenWidth.WIDE else ScreenWidth.COMPACT

@Composable
@ReadOnlyComposable
fun isWideScreen(): Boolean = screenWidth() == ScreenWidth.WIDE

/**
 * Сколько плиток ставить в ряд.
 *
 * Считается от ширины, а не от разряда устройства: на 1280 dp четыре плитки
 * читаются с водительского места, а восемь - уже нет, цифры становятся мелкими.
 * Две - телефонный минимум, ниже опускаться некуда.
 */
@Composable
@ReadOnlyComposable
fun tileColumns(): Int {
    val dp = LocalConfiguration.current.screenWidthDp
    return when {
        dp >= 1100 -> 4
        dp >= WIDE_SCREEN_DP -> 3
        else -> 2
    }
}

/**
 * Размеры под то, как на экран смотрят.
 *
 * На телефоне его держат в руках и разглядывают. На магнитоле в него
 * заглядывают боковым зрением на ходу, с расстояния вытянутой руки и в
 * трясущейся машине - и там решает не красота, а то, читается ли цифра за
 * полсекунды. Поэтому на широком экране значения заметно крупнее, а отступы,
 * наоборот, меньше: чем плотнее, тем больше параметров видно без прокрутки, а
 * прокручивать за рулём нельзя.
 *
 * Подписи не растут вместе со значениями: их читают один раз, когда
 * разбираются, что где, и крупными они только отнимали бы место у цифр.
 */
@Immutable
data class UiScale(
    /** Значение в плитке - самое важное число на экране. */
    val value: TextUnit,
    /** Число внутри круглой шкалы. */
    val gaugeValue: TextUnit,
    /** Подпись над значением. */
    val label: TextUnit,
    /** Пояснения и расшифровки - то, что приходят прочитать, а не окинуть взглядом. */
    val hint: TextUnit,
    val panelPadding: Dp,
    val panelGap: Dp,
    val screenPadding: Dp,
    val screenGap: Dp,
    /** Больше этого круглая шкала не растёт. */
    val arcWidth: Dp,
)

private val PHONE_SCALE = UiScale(
    value = 22.sp,
    gaugeValue = 28.sp,
    label = 12.sp,
    hint = 13.sp,
    panelPadding = 14.dp,
    panelGap = 8.dp,
    screenPadding = 14.dp,
    screenGap = 12.dp,
    arcWidth = 260.dp,
)

private val CAR_SCALE = UiScale(
    value = 32.sp,
    gaugeValue = 48.sp,
    label = 15.sp,
    hint = 16.sp,
    panelPadding = 10.dp,
    panelGap = 4.dp,
    screenPadding = 10.dp,
    screenGap = 8.dp,
    arcWidth = 300.dp,
)

@Composable
@ReadOnlyComposable
fun uiScale(): UiScale = if (isWideScreen()) CAR_SCALE else PHONE_SCALE

/**
 * Размер шрифта, который растёт вместе с настройкой, но не бесконечно.
 *
 * Нужен служебным подписям - вкладкам внизу и кнопкам-переключателям. Они
 * короткие, стоят в ряд и делят ширину экрана поровну: когда человек ставит
 * двойной размер текста, подпись "Приборы" перестаёт помещаться в пятую часть
 * экрана и переносится в три строки поверх значка, а "Bluetooth" на кнопке
 * обрезается на полуслове.
 *
 * Содержимое экранов при этом растёт как просили - ограничивается только
 * обрамление. Это не самоуправство: размер текста увеличивают, чтобы читать
 * данные, а не названия вкладок, которые и так узнают по значку и месту.
 *
 * Считается через плотность: текст в sp система сама умножает на масштаб
 * шрифта, поэтому чтобы получить нужный предел, масштаб надо из размера
 * поделить обратно.
 */
@Composable
@ReadOnlyComposable
fun cappedSp(baseSp: Float, maxScale: Float = 1.3f): TextUnit {
    val scale = LocalDensity.current.fontScale
    if (scale <= maxScale || scale <= 0f) return baseSp.sp
    return (baseSp * maxScale / scale).sp
}
