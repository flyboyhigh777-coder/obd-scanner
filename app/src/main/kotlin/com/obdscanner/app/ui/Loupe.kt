package com.obdscanner.app.ui

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.magnifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Лупа по долгому нажатию.
 *
 * Нужна для того же, для чего её носят в кармане: прочитать мелкое, не
 * настраивая ничего заранее. Размер текста в приложении меняется в настройках,
 * но это для постоянного чтения, а тут другое - "не разглядеть вот эту строку
 * прямо сейчас": код ошибки в отчёте, значение в таблице, VIN. Ради одной
 * строки лезть в настройки и потом возвращать всё назад никто не будет.
 *
 * Лупа системная (android.widget.Magnifier): она показывает то, что реально
 * нарисовано на экране, какой бы экран это ни был, - не нужно ни
 * предусматривать её на каждом экране, ни держать второй, "крупный" вариант
 * вёрстки. Появилась она в Android 9; на более старых (у нас поддерживается
 * с Android 8) долгое нажатие просто ничего не делает, и подсказку про лупу
 * там не показываем - см. [loupeAvailable].
 *
 * Жест забирается себе целиком: пока лупа на экране, страница под пальцем не
 * прокручивается, а кнопка под ним не нажмётся, когда палец отпустят. Иначе
 * рассматривание кнопки "Сохранить PDF" заканчивалось бы сохранением PDF.
 *
 * На приборке лупы нет: там долгое нажатие берёт плитку.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LoupeArea(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val supported = remember { loupeAvailable() }
    if (!supported) {
        Box(modifier, content = content)
        return
    }

    val haptics = LocalHapticFeedback.current
    // Где сейчас палец. Offset.Unspecified означает "лупы нет".
    var finger by remember { mutableStateOf(Offset.Unspecified) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val latestEnabled by rememberUpdatedState(enabled)

    // Размер стекла - от размера экрана: на телефоне почти во всю ширину, на
    // магнитоле не больше предела, иначе оно закроет собой всю страницу.
    val density = LocalDensity.current
    val lens = with(density) {
        DpSize(
            width = LoupePlacement.lensWidth(area.width.toDp().value).dp,
            height = LoupePlacement.lensHeight(area.height.toDp().value).dp
        )
    }

    Box(
        modifier
            .onSizeChanged { area = it }
            .loupeGesture(enabled = { latestEnabled }) { point ->
                // Короткая вибрация в момент появления - то же, чем телефон
                // отвечает на долгое нажатие везде: понятно, что лупа взялась,
                // не приглядываясь к ней.
                if (point != Offset.Unspecified && finger == Offset.Unspecified) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                finger = point
            }
            .magnifier(
                sourceCenter = { finger },
                magnifierCenter = {
                    if (finger == Offset.Unspecified) {
                        Offset.Unspecified
                    } else {
                        LoupePlacement.lensCenter(
                            sourceX = finger.x,
                            sourceY = finger.y,
                            areaWidth = area.width.toFloat(),
                            areaHeight = area.height.toFloat(),
                            lensWidth = lens.width.toPx(),
                            lensHeight = lens.height.toPx(),
                            gap = LENS_GAP.toPx()
                        )
                    }
                },
                zoom = LENS_ZOOM,
                size = lens,
                cornerRadius = 20.dp,
                elevation = 10.dp
            ),
        content = content
    )
}

/**
 * Сам жест лупы, отдельно от неё.
 *
 * Отдельно - потому что проверить можно только его: системную лупу рисует
 * система в своём окне, тест её не видит и в тестовой среде она вообще не
 * заводится (нет настоящей поверхности). А ломается как раз жест: кто
 * первым получит нажатие, забирает ли лупа его себе, срабатывает ли кнопка
 * под пальцем.
 *
 * [onFinger] получает место пальца, пока лупа открыта, и [Offset.Unspecified],
 * когда она закрылась. Лямбда должна быть простой записью в состояние:
 * корутина жеста запускается один раз и держит ту лямбду, что была при
 * запуске.
 */
fun Modifier.loupeGesture(
    enabled: () -> Boolean = { true },
    onFinger: (Offset) -> Unit
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        // requireUnconsumed = false: кнопки и строки меню забирают нажатие
        // себе сразу (так работает clickable), и лупа, которая ждала бы
        // непотреблённого нажатия, над ними не открывалась бы вовсе - а
        // отчёт как раз состоит из строк, по которым куда-то переходят.
        val down = awaitFirstDown(requireUnconsumed = false)
        // Ждём порога долгого нажатия. Ушёл палец в сторону - это прокрутка,
        // и жест забрал кто-то другой; отпустили раньше - это обычное
        // касание, и оно не наше.
        //
        // withTimeoutOrNull здесь - метод самого жеста, а не общий из
        // корутин: он отмеряет время по событиям ввода. Общий считает по
        // часам, которых в тестах нет, и там долгое нажатие не наступало бы
        // никогда.
        var cancelled = false
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation().also { if (it == null) cancelled = true }
        }
        if (up != null || cancelled || !enabled()) return@awaitEachGesture

        onFinger(down.position)
        try {
            while (true) {
                // Initial - потому что по этому проходу события идут сверху
                // вниз, от нас к детям. Забираем всё себе раньше них: и
                // движение, чтобы не ехала страница, и отпускание, чтобы не
                // сработала кнопка, которую разглядывали. На обычном проходе
                // дети получают событие первыми, и кнопка успела бы нажаться
                // до нас.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                onFinger(change.position)
            }
        } finally {
            onFinger(Offset.Unspecified)
        }
    }
}

/**
 * Есть ли в этой системе лупа.
 *
 * android.widget.Magnifier появился в Android 9. Compose проверяет это и сам,
 * но молча - просто не добавляет модификатор; нам же нужно знать заранее,
 * чтобы не обещать лупу подсказкой там, где её не будет.
 */
fun loupeAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

/**
 * Куда положить саму лупу относительно пальца.
 *
 * Отдельно от экрана и без Compose: тут легко ошибиться знаком или краем, а
 * ловить это пальцем на телефоне долго.
 */
object LoupePlacement {

    /**
     * Лупа висит над пальцем: под пальцем лежит то, что рассматривают, и
     * накрывать его стеклом бессмысленно. У верхнего края экрана, где над
     * пальцем места нет, она переезжает под него. По бокам упирается в края,
     * чтобы не уехала за экран половиной.
     */
    fun lensCenter(
        sourceX: Float,
        sourceY: Float,
        areaWidth: Float,
        areaHeight: Float,
        lensWidth: Float,
        lensHeight: Float,
        gap: Float
    ): Offset {
        val halfWidth = lensWidth / 2f
        val halfHeight = lensHeight / 2f
        // Если лупа шире области (узкий экран, крупная лупа) - просто по центру.
        val x = if (lensWidth >= areaWidth) {
            areaWidth / 2f
        } else {
            sourceX.coerceIn(halfWidth, areaWidth - halfWidth)
        }
        val above = sourceY - gap - halfHeight
        val y = if (above - halfHeight >= 0f) {
            above
        } else {
            // Под палец, но не ниже нижнего края.
            min(sourceY + gap + halfHeight, max(areaHeight - halfHeight, halfHeight))
        }
        return Offset(x, y)
    }

    /**
     * Размер стекла - от размера экрана, а не числом из кода.
     *
     * Увеличение 2,2 означает, что в стекло влезает кусок экрана в 2,2 раза
     * меньше его самого: стекло в треть экрана показывает седьмую часть
     * строки - два слова и обрыв. Поэтому оно почти во всю ширину: строку
     * целиком при таком увеличении не показать никак, но чем шире стекло,
     * тем меньше приходится возить пальцем.
     *
     * Сверху всё же ограничено: на широком экране магнитолы стекло во всю
     * ширину закрыло бы собой то, ради чего в него смотрят, - понимание,
     * где ты на странице.
     */
    fun lensWidth(areaWidth: Float): Float {
        // Первый кадр: экран ещё не измерен. Нулевое стекло система не
        // принимает (Magnifier.Builder требует положительный размер) и роняет
        // приложение, поэтому до замера берём наименьшее разумное.
        if (areaWidth <= 0f) return MIN_WIDTH
        return min(areaWidth * WIDTH_SHARE, MAX_WIDTH).coerceAtLeast(min(MIN_WIDTH, areaWidth))
    }

    fun lensHeight(areaHeight: Float): Float {
        if (areaHeight <= 0f) return MIN_HEIGHT
        return min(areaHeight * HEIGHT_SHARE, MAX_HEIGHT).coerceAtLeast(min(MIN_HEIGHT, areaHeight))
    }

    /** Доли и пределы - в тех же единицах, что и площадь (у нас это dp). */
    private const val WIDTH_SHARE = 0.92f
    private const val HEIGHT_SHARE = 0.3f
    private const val MIN_WIDTH = 220f
    private const val MIN_HEIGHT = 90f
    private const val MAX_WIDTH = 460f
    private const val MAX_HEIGHT = 170f
}

/** Во сколько раз увеличивать. Больше трёх - видно пиксели, меньше двух - незачем. */
private const val LENS_ZOOM = 2.2f

/** Насколько поднять её над пальцем, чтобы он не загораживал строку. */
private val LENS_GAP = 36.dp
