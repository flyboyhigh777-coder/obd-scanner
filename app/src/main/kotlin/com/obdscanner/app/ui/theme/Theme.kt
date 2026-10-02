package com.obdscanner.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Фиксированная тёмная схема: приложением пользуются в машине, часто в темноте,
 * и светлая тема тут только слепит. Системная тема и Material You сознательно
 * не учитываются - цвета шкал (бирюза, янтарь, красный) должны значить одно и
 * то же на любом телефоне.
 */
private val ObdColorScheme = darkColorScheme(
    primary = SegmentSelected,
    onPrimary = SegmentSelectedText,
    primaryContainer = GraphiteHigh,
    onPrimaryContainer = TextBright,
    secondary = GaugeCyan,
    onSecondary = SegmentSelectedText,
    tertiary = GaugeBlue,
    onTertiary = TextBright,
    background = Carbon,
    onBackground = TextBright,
    surface = Graphite,
    onSurface = TextBright,
    surfaceVariant = GraphiteHigh,
    onSurfaceVariant = TextMuted,
    outline = OutlineGray,
    error = GaugeRed,
    onError = TextBright,
    errorContainer = androidx.compose.ui.graphics.Color(0xFF4A1F24),
    onErrorContainer = TextBright
)

@Composable
fun ObdScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ObdColorScheme,
        typography = ObdTypography,
        content = content
    )
}
