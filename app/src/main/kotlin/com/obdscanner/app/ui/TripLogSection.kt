package com.obdscanner.app.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.OpenTripLog
import com.obdscanner.app.R
import com.obdscanner.app.data.TripLogEntry
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeCyan
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.InfoRow
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.PanelTitle
import com.obdscanner.app.ui.theme.SegmentButton
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted
import com.obdscanner.app.ui.theme.uiScale
import com.obdscanner.core.obd.ObdPid
import com.obdscanner.core.obd.ObdUnit
import com.obdscanner.core.obd.UnitSystem
import com.obdscanner.core.pro.ProFeature
import com.obdscanner.core.trip.TripLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал поездок - «чёрный ящик».
 *
 * Приборка отвечает на вопрос «что сейчас», и всё это пропадает вместе со
 * связью. А вопросы возникают потом: какая была температура на том подъёме,
 * когда именно просели обороты, держался ли расход. Поэтому показания
 * пишутся всю дорогу, а здесь их читают.
 *
 * Читают именно здесь, а не в таблице на компьютере. Выгрузка в CSV у
 * приложения была и раньше - и оказалась бесполезной: две тысячи строк с
 * английскими заголовками не отвечают ни на один человеческий вопрос. Файл
 * остался (кому надо - разберёт), но ответ на вопрос должен давать экран:
 * выбрал параметр - увидел, как он менялся, и сколько было самое большое и
 * самое маленькое.
 */
@Composable
fun TripLogSection(viewModel: ObdViewModel) {
    val access by viewModel.access.collectAsState()
    val open by viewModel.openTripLog.collectAsState()
    var proFor by remember { mutableStateOf<ProFeature?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshTripLogs() }
    proFor?.let { feature -> ProDialog(feature) { proFor = null } }

    if (!access.allows(ProFeature.TRIP)) {
        Panel {
            PanelTitle(stringResource(R.string.triplog_title))
            Hint(stringResource(R.string.triplog_hint))
            ProButton(stringResource(R.string.pro_title), Modifier.fillMaxWidth()) {
                proFor = ProFeature.TRIP
            }
        }
        return
    }

    val opened = open
    if (opened != null) {
        TripLogDetail(viewModel, opened)
        return
    }
    TripLogList(viewModel)
}

@Composable
private fun TripLogList(viewModel: ObdViewModel) {
    val logs by viewModel.tripLogs.collectAsState()

    Panel {
        PanelTitle(stringResource(R.string.triplog_title))
        Hint(stringResource(R.string.triplog_hint))
        if (logs.isEmpty()) {
            Hint(stringResource(R.string.triplog_empty))
            return@Panel
        }
        logs.forEach { entry -> TripLogRow(entry) { viewModel.openTripLog(entry) } }
    }
}

/**
 * Строка поездки.
 *
 * Слева - когда это было, справа - сколько ехали и сколько проехали. Это те
 * два числа, по которым человек узнаёт свою поездку среди других; имя файла и
 * его размер не говорят ничего.
 */
@Composable
private fun TripLogRow(entry: TripLogEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tripDate(entry.startMillis),
                style = MaterialTheme.typography.titleSmall,
                color = TextBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val summary = entry.summary
            Hint(
                when {
                    summary == null -> stringResource(R.string.triplog_unfinished)
                    else -> listOfNotNull(
                        summary.durationMinutes?.let {
                            stringResource(R.string.triplog_minutes, it.toInt())
                        },
                        summary.distanceKm?.takeIf { it > 0.05 }?.let {
                            stringResource(R.string.triplog_km, "%.1f".format(Locale.getDefault(), it))
                        },
                        summary.maxCoolantC?.let {
                            stringResource(R.string.triplog_max_coolant, it.toInt())
                        }
                    ).joinToString(" · ")
                }
            )
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = TextMuted)
    }
}

@Composable
private fun TripLogDetail(viewModel: ObdViewModel, open: OpenTripLog) {
    val context = LocalContext.current
    val units by viewModel.units.collectAsState()
    var pid by remember(open.entry.name) { mutableStateOf(open.parameters.firstOrNull()) }

    Panel {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { viewModel.closeTripLog() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("‹", style = MaterialTheme.typography.headlineSmall, color = TextMuted)
            Text(
                stringResource(R.string.triplog_back),
                color = TextMuted,
                maxLines = 1
            )
        }
        PanelTitle(tripDate(open.entry.startMillis))

        val stats = open.stats
        InfoRow(
            stringResource(R.string.trip_duration),
            Format.duration(stats.durationMillis)
        )
        InfoRow(
            stringResource(R.string.trip_distance),
            Format.value(stats.distanceKm, ObdUnit.KM, 1, units)
        )
        InfoRow(
            stringResource(R.string.trip_max_speed),
            Format.value(stats.maxSpeedKmh, ObdUnit.KMH, 0, units)
        )
        stats.maxCoolantC?.let {
            InfoRow(stringResource(R.string.trip_max_coolant), Format.value(it, ObdUnit.CELSIUS, 0, units))
        }
        stats.consumptionL100Km?.let {
            InfoRow(
                stringResource(R.string.dash_consumption_average),
                Format.value(it, ObdUnit.LITRE_PER_100KM, 1, units)
            )
        }
        Hint(stringResource(R.string.triplog_samples, open.file.samples.size))
    }

    Panel {
        PanelTitle(stringResource(R.string.triplog_parameter))
        // Выбор параметра полосой: их в поездке десяток-другой, и список
        // галочек занял бы весь экран ради одного касания.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            open.parameters.forEach { item ->
                SegmentButton(item.title, item.id == pid?.id) { pid = item }
            }
        }

        val chosen = pid
        if (chosen == null) {
            Hint(stringResource(R.string.triplog_no_data))
            return@Panel
        }
        val series = remember(open.entry.name, chosen.id) { TripLog.series(open.file, chosen.id) }
        if (series.isEmpty) {
            Hint(stringResource(R.string.triplog_no_data))
            return@Panel
        }

        TripLogChart(series, chosen, units)

        InfoRow(stringResource(R.string.triplog_min), Format.value(chosen, series.min, units))
        InfoRow(stringResource(R.string.triplog_avg), Format.value(chosen, series.average, units))
        InfoRow(stringResource(R.string.triplog_max), Format.value(chosen, series.max, units))
    }

    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val uri = FileProvider.getUriForFile(
                        context,
                        context.packageName + ".fileprovider",
                        open.entry.file
                    )
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            null
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.triplog_share)) }
            OutlinedButton(
                onClick = { viewModel.deleteTripLog(open.entry) },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.triplog_delete), color = GaugeAmber) }
        }
        Hint(stringResource(R.string.triplog_share_hint))
    }
}

/**
 * График параметра за поездку.
 *
 * Своя отрисовка, а не библиотека: нужна одна ломаная линия и две подписи, а
 * готовая библиотека графиков - это мегабайт в сборке и чужой набор шрифтов и
 * цветов посреди нашего экрана.
 *
 * Шкала - от минимума до максимума самой поездки, а не от пределов датчика:
 * температура, которая весь путь держалась между 88 и 92 градусами, на шкале
 * «0-150» выглядит прямой линией, хотя разглядеть хотели именно эти четыре
 * градуса.
 */
@Composable
private fun TripLogChart(series: TripLog.Series, pid: ObdPid, units: UnitSystem) {
    val scale = uiScale()
    val label = @Composable { text: String ->
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = scale.hint),
            color = TextMuted
        )
    }
    Column(Modifier.fillMaxWidth()) {
        // Подписи стоят там, где им место на графике: максимум - у верхнего
        // края, минимум - у нижнего. Рядом друг с другом они читались бы как
        // «от и до», а это разные края картинки.
        label(Format.value(pid, series.max, units))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(vertical = 6.dp)
        ) {
            val points = series.points
            if (points.size < 2) return@Canvas
            val spanSeconds = (points.last().seconds - points.first().seconds).takeIf { it > 0.0 } ?: 1.0
            // Параметр, который всю поездку не менялся (температура на
            // прогретом моторе, ровные обороты), - это прямая линия. Рисуем
            // её посередине: по нижнему краю она читается как «упало в ноль».
            val flat = series.max - series.min <= 0.0
            val spanValue = (series.max - series.min).takeIf { it > 0.0 } ?: 1.0

            // Три линии сетки: верх, середина, низ. Больше - рябит.
            repeat(3) { index ->
                val y = size.height * index / 2f
                drawLine(
                    color = TextMuted.copy(alpha = 0.18f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
            }

            val path = Path()
            points.forEachIndexed { index, point ->
                val x = ((point.seconds - points.first().seconds) / spanSeconds).toFloat() * size.width
                val y = if (flat) {
                    size.height / 2f
                } else {
                    size.height - ((point.value - series.min) / spanValue).toFloat() * size.height
                }
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = GaugeCyan,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            label(Format.value(pid, series.min, units))
            label(
                stringResource(R.string.triplog_start) + " … " +
                    Format.duration((series.points.last().seconds * 1000).toLong())
            )
        }
    }
}

/** «25 октября, 18:27» - то, по чему человек узнаёт свою поездку. */
private fun tripDate(millis: Long): String =
    SimpleDateFormat("d MMMM, HH:mm", Locale.getDefault()).format(Date(millis))
