package com.obdscanner.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

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
