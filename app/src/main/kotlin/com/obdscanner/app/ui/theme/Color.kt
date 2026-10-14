package com.obdscanner.app.ui.theme

import androidx.compose.ui.graphics.Color

// Палитра приборной панели: тёмный графит как фон (не чёрный - на чёрном
// дешёвые OLED-телефоны мажут движущиеся цифры), бирюзовый как основной
// акцент шкал, янтарный и красный - для предупреждений. Такой набор читается
// и в темноте салона, и при солнце через лобовое стекло.
val Carbon = Color(0xFF0E1116)
val CarbonDeep = Color(0xFF080A0E)
val Graphite = Color(0xFF161B22)
val GraphiteHigh = Color(0xFF1E252F)
val PanelFill = Color(0x14FFFFFF)
val PanelBorder = Color(0x26FFFFFF)

val GaugeCyan = Color(0xFF2ED3C6)
val GaugeBlue = Color(0xFF3B82F6)
val GaugeAmber = Color(0xFFFFB02E)
val GaugeRed = Color(0xFFFF5A5A)
val GaugeGreen = Color(0xFF4ADE80)

val TextBright = Color(0xFFF2F5F8)
val TextMuted = Color(0xFF93A1B0)
val TextDim = Color(0xFF6B7885)

val SegmentSelected = Color(0xFFE6EDF3)
val SegmentSelectedText = Color(0xFF10151B)
val OutlineGray = Color(0x40FFFFFF)

// --- цвета круглых значков в списках ------------------------------------
//
// Схема "круглый цветной значок слева, название и описание справа" взята из
// чужой программы (так устроены почти все сканеры), а сами цвета - свои:
// у неё светлая тема и яркие значки Material 2, у нас тёмный графит, и те же
// цвета на нём выжигают глаза ночью. Поэтому набор приглушённый, с одинаковой
// светлотой - на тёмном фоне иначе один значок кажется ближе другого.
//
// Зелёный, янтарный и красный не трогаем: это светофор, и значение у него
// общепринятое. Остальные - для разделов, где состояния нет.
val CircleSteel = Color(0xFF3D6E9E)
val CircleSage = Color(0xFF3C8F62)
val CircleAmber = Color(0xFFB5802C)
val CircleCrimson = Color(0xFFB0453F)
val CircleSlate = Color(0xFF4A5565)
val CirclePlum = Color(0xFF6A5190)

/** Фон "таблетки" заголовка: сталь в сливовый - свой, не бирюза в синий. */
val HeaderStart = Color(0xFF1B3B5A)
val HeaderEnd = Color(0xFF3E2C5A)
