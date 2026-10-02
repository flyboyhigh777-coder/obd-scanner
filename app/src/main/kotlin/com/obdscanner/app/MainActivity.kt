package com.obdscanner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ui.ConnectScreen
import com.obdscanner.app.ui.DashboardScreen
import com.obdscanner.app.ui.DtcScreen
import com.obdscanner.app.ui.TripScreen
import com.obdscanner.app.ui.VehicleScreen
import com.obdscanner.app.ui.theme.Carbon
import com.obdscanner.app.ui.theme.CarbonDeep
import com.obdscanner.app.ui.theme.ObdScannerTheme
import com.obdscanner.app.ui.theme.PanelFill
import com.obdscanner.app.ui.theme.SegmentSelected
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted

/**
 * Единственная Activity. Пять вкладок внизу, у всех общий [ObdViewModel]: связь
 * с машиной одна, и переключение вкладок не должно её рвать.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ObdViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ObdScannerTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Carbon, CarbonDeep, Carbon)))
                ) {
                    // Голый Box не прокидывает цвет текста по умолчанию (это делает
                    // Surface), и без этого текст без явного color рисуется чёрным
                    // и исчезает на тёмном фоне.
                    CompositionLocalProvider(LocalContentColor provides TextBright) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    0 -> ConnectScreen(viewModel)
                                    1 -> DashboardScreen(viewModel)
                                    2 -> DtcScreen(viewModel)
                                    3 -> VehicleScreen(viewModel)
                                    else -> TripScreen(viewModel)
                                }
                            }
                            NavigationBar(containerColor = PanelFill, contentColor = TextBright) {
                                NavigationBarItem(
                                    selected = tab == 0,
                                    onClick = { tab = 0 },
                                    icon = { PlugIcon() },
                                    label = { Text(stringResource(R.string.tab_connect)) },
                                    colors = navColors()
                                )
                                NavigationBarItem(
                                    selected = tab == 1,
                                    onClick = { tab = 1 },
                                    icon = { GaugeIcon() },
                                    label = { Text(stringResource(R.string.tab_dashboard)) },
                                    colors = navColors()
                                )
                                NavigationBarItem(
                                    selected = tab == 2,
                                    onClick = { tab = 2 },
                                    icon = { WarningIcon() },
                                    label = { Text(stringResource(R.string.tab_dtc)) },
                                    colors = navColors()
                                )
                                NavigationBarItem(
                                    selected = tab == 3,
                                    onClick = { tab = 3 },
                                    icon = { CarIcon() },
                                    label = { Text(stringResource(R.string.tab_vehicle)) },
                                    colors = navColors()
                                )
                                NavigationBarItem(
                                    selected = tab == 4,
                                    onClick = { tab = 4 },
                                    icon = { RouteIcon() },
                                    label = { Text(stringResource(R.string.tab_trip)) },
                                    colors = navColors()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = TextBright,
    selectedTextColor = TextBright,
    unselectedIconColor = TextMuted,
    unselectedTextColor = TextMuted,
    indicatorColor = SegmentSelected.copy(alpha = 0.20f)
)

/** Диагностический разъём: трапеция с контактами. */
@Composable
private fun PlugIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        val stroke = 2.dp.toPx()
        val body = Path().apply {
            moveTo(w * 0.14f, h * 0.34f); lineTo(w * 0.86f, h * 0.34f)
            lineTo(w * 0.74f, h * 0.70f); lineTo(w * 0.26f, h * 0.70f); close()
        }
        drawPath(body, color, style = Stroke(stroke))
        for (k in 0 until 3) {
            val x = w * (0.33f + 0.17f * k)
            drawLine(color, Offset(x, h * 0.44f), Offset(x, h * 0.60f), strokeWidth = stroke)
        }
    }
}

/** Шкала со стрелкой - приборы. */
@Composable
private fun GaugeIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        val stroke = 2.dp.toPx()
        val arc = Path().apply {
            addArc(Rect(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.94f), -170f, 160f)
        }
        drawPath(arc, color, style = Stroke(stroke))
        drawLine(color, Offset(w / 2, h * 0.68f), Offset(w * 0.72f, h * 0.34f), strokeWidth = stroke)
        drawCircle(color, radius = 1.8.dp.toPx(), center = Offset(w / 2, h * 0.68f))
    }
}

/** Треугольник с восклицательным знаком - ошибки. */
@Composable
private fun WarningIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        val stroke = 2.dp.toPx()
        val triangle = Path().apply {
            moveTo(w / 2, h * 0.14f); lineTo(w * 0.92f, h * 0.84f); lineTo(w * 0.08f, h * 0.84f); close()
        }
        drawPath(triangle, color, style = Stroke(stroke))
        drawLine(color, Offset(w / 2, h * 0.40f), Offset(w / 2, h * 0.62f), strokeWidth = stroke)
        drawCircle(color, radius = 1.2.dp.toPx(), center = Offset(w / 2, h * 0.72f))
    }
}

/** Силуэт машины. */
@Composable
private fun CarIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        val stroke = 2.dp.toPx()
        val body = Path().apply {
            moveTo(w * 0.10f, h * 0.64f); lineTo(w * 0.18f, h * 0.44f)
            lineTo(w * 0.34f, h * 0.34f); lineTo(w * 0.68f, h * 0.34f)
            lineTo(w * 0.84f, h * 0.46f); lineTo(w * 0.90f, h * 0.64f)
        }
        drawPath(body, color, style = Stroke(stroke))
        drawLine(color, Offset(w * 0.10f, h * 0.64f), Offset(w * 0.90f, h * 0.64f), strokeWidth = stroke)
        drawCircle(color, radius = 2.4.dp.toPx(), center = Offset(w * 0.30f, h * 0.70f))
        drawCircle(color, radius = 2.4.dp.toPx(), center = Offset(w * 0.70f, h * 0.70f))
    }
}

/** Извилистая дорога - поездка. */
@Composable
private fun RouteIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        val stroke = 2.dp.toPx()
        val road = Path().apply {
            moveTo(w * 0.28f, h * 0.88f)
            cubicTo(w * 0.28f, h * 0.56f, w * 0.72f, h * 0.60f, w * 0.72f, h * 0.30f)
        }
        drawPath(road, color, style = Stroke(stroke))
        drawCircle(color, radius = 2.6.dp.toPx(), center = Offset(w * 0.28f, h * 0.88f))
        drawCircle(color, radius = 2.6.dp.toPx(), center = Offset(w * 0.72f, h * 0.22f))
    }
}
