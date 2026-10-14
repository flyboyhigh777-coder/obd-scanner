package com.obdscanner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ui.ConnectScreen
import com.obdscanner.app.ui.DashboardScreen
import com.obdscanner.app.ui.DiagnosticsScreen
import com.obdscanner.app.ui.MeasureScreen
import com.obdscanner.app.ui.VehicleScreen
import com.obdscanner.app.ui.theme.Carbon
import com.obdscanner.app.ui.theme.CarbonDeep
import com.obdscanner.app.ui.theme.ObdScannerTheme
import com.obdscanner.app.ui.theme.isWideScreen
import com.obdscanner.app.ui.theme.PanelFill
import com.obdscanner.app.ui.theme.SegmentSelected
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted

/**
 * Единственная Activity. Пять вкладок: связь, приборы, диагностика, замеры и
 * данные автомобиля; разделы диагностики - меню внутри своей вкладки. У всех общий [ObdViewModel] - связь с машиной одна, и
 * переключение вкладок не должно её рвать, а замеры разгона не должны
 * обнуляться от того, что человек заглянул в ошибки.
 *
 * Вкладки живут снизу на телефоне и сбоку на широком экране. Это не
 * украшательство: у головного устройства в машине экран широкий и низкий
 * (1024x600, 1280x720), и полоса навигации снизу съедает высоту, которой там
 * и так мало.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ObdViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ObdScannerTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                KeepScreenOnWhileConnected(viewModel)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Carbon, CarbonDeep, Carbon)))
                ) {
                    // Голый Box не прокидывает цвет текста по умолчанию (это делает
                    // Surface), и без этого текст без явного color рисуется чёрным
                    // и исчезает на тёмном фоне.
                    // Размер текста задаётся через плотность: так увеличивается
                    // весь текст приложения сразу, включая диалоги и описания
                    // ошибок, а не только там, где про это не забыли. Сам
                    // размер в sp не трогаем - меняется множитель, на который
                    // система их переводит в пиксели.
                    val textScale by viewModel.textScale.collectAsState()
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalContentColor provides TextBright,
                        LocalDensity provides Density(density.density, density.fontScale * textScale),
                    ) {
                        val wide = isWideScreen()
                        val content = @Composable { modifier: Modifier ->
                            Box(modifier) {
                                when (tab) {
                                    0 -> ConnectScreen(viewModel)
                                    1 -> DashboardScreen(viewModel)
                                    2 -> DiagnosticsScreen(viewModel)
                                    3 -> MeasureScreen(viewModel)
                                    else -> VehicleScreen(viewModel)
                                }
                            }
                        }
                        if (wide) {
                            Row(Modifier.fillMaxSize()) {
                                TabRail(tab) { tab = it }
                                content(Modifier.weight(1f))
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                content(Modifier.weight(1f))
                                TabBar(tab) { tab = it }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Пять вкладок одним списком: порядок и подписи у полосы и у рейки общие.
 *
 * Разделов диагностики шесть, и вкладкой каждый не сделать - на телефоне 360 dp
 * подписи начинают обрезаться уже на шестой. Поэтому они собраны под одной
 * вкладкой "Проверка", которая показывает их меню с состоянием каждого
 * раздела: сколько кодов, завершены ли тесты, есть ли тест вне пределов.
 *
 * Подпись у вкладки короткая, а заголовок внутри полный ("Диагностика"):
 * одиннадцать букв в пятой вкладке на телефоне 360 dp переносятся на вторую
 * строку и обрезаются.
 */
private val TABS = listOf(
    R.string.tab_connect,
    R.string.tab_dashboard,
    R.string.tab_diag,
    R.string.tab_measure,
    R.string.tab_vehicle,
)

@Composable
private fun TabIcon(index: Int) {
    when (index) {
        0 -> PlugIcon()
        1 -> GaugeIcon()
        2 -> WarningIcon()
        3 -> RouteIcon()
        else -> CarIcon()
    }
}

/** Телефон в портрете: полоса снизу, под большой палец. */
@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = PanelFill, contentColor = TextBright) {
        TABS.forEachIndexed { index, title ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { TabIcon(index) },
                label = { Text(stringResource(title)) },
                colors = navColors()
            )
        }
    }
}

/**
 * Широкий экран: рейка слева.
 *
 * Слева, а не справа, потому что в машине с левым рулём это ближняя к
 * водителю сторона экрана, и до неё дотягиваться, не наклоняясь.
 */
@Composable
private fun TabRail(selected: Int, onSelect: (Int) -> Unit) {
    NavigationRail(containerColor = PanelFill, contentColor = TextBright) {
        TABS.forEachIndexed { index, title ->
            NavigationRailItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { TabIcon(index) },
                label = { Text(stringResource(title)) },
                colors = railColors()
            )
        }
    }
}

/**
 * Пока идёт связь с машиной, экран не гасим.
 *
 * На головном устройстве это обязательно: приборы, которые тухнут через
 * минуту, бесполезны. На телефоне это тоже верно - человек смотрит на цифры, а
 * не трогает экран, - но только пока есть подключение, иначе приложение просто
 * сажало бы батарею.
 */
@Composable
private fun KeepScreenOnWhileConnected(viewModel: ObdViewModel) {
    val connection by viewModel.connection.collectAsState()
    val view = LocalView.current
    val connected = connection is ConnectionState.Connected
    DisposableEffect(connected) {
        view.keepScreenOn = connected
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun railColors() = NavigationRailItemDefaults.colors(
    selectedIconColor = TextBright,
    selectedTextColor = TextBright,
    unselectedIconColor = TextMuted,
    unselectedTextColor = TextMuted,
    indicatorColor = SegmentSelected.copy(alpha = 0.20f)
)

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
