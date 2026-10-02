package com.obdscanner.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.R
import com.obdscanner.app.data.TripExport
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeGreen
import com.obdscanner.app.ui.theme.GaugeRed
import com.obdscanner.app.ui.theme.GradientTopBar
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.InfoRow
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.PanelTitle
import com.obdscanner.app.ui.theme.SegmentButton
import com.obdscanner.app.ui.theme.StatusDot
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.core.obd.ObdUnit
import com.obdscanner.core.obd.UnitConversion
import com.obdscanner.core.perf.AccelerationRun

/**
 * Замеры: поездка целиком (пробег, расход, выгрузка в CSV) и отдельные заезды на
 * разгон с оценкой мощности.
 *
 * Два раздела под одной вкладкой, потому что данные у них одни и те же - поток
 * скорости и оборотов, - а смотрят на них в разные моменты: поездку после
 * дороги, разгон сразу после заезда.
 */
@Composable
fun MeasureScreen(viewModel: ObdViewModel) {
    var section by rememberSaveable { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GradientTopBar(stringResource(R.string.measure_title))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentButton(stringResource(R.string.measure_tab_trip), section == 0, Modifier.weight(1f)) {
                section = 0
            }
            SegmentButton(stringResource(R.string.measure_tab_accel), section == 1, Modifier.weight(1f)) {
                section = 1
            }
        }
        if (section == 0) TripSection(viewModel) else AccelerationSection(viewModel)
    }
}

@Composable
private fun TripSection(viewModel: ObdViewModel) {
    val context = LocalContext.current
    val connection by viewModel.connection.collectAsState()
    val trip by viewModel.trip.collectAsState()
    val units by viewModel.units.collectAsState()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf(false) }

    val savedText = stringResource(R.string.trip_exported)
    val sharedText = stringResource(R.string.trip_exported_share)
    val failedText = stringResource(R.string.trip_export_failed)
    val stats = trip.stats

    Panel {
        Hint(stringResource(R.string.trip_hint))
        if (trip.recording) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(GaugeRed)
                Text(stringResource(R.string.trip_recording), color = TextBright)
            }
            Button(
                onClick = viewModel::stopRecording,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = GaugeAmber, contentColor = TextBright)
            ) { Text(stringResource(R.string.trip_stop)) }
        } else {
            Button(
                onClick = viewModel::startRecording,
                enabled = connection is ConnectionState.Connected,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.trip_start)) }
            if (connection !is ConnectionState.Connected) Hint(stringResource(R.string.dash_offline))
        }
    }

    if (stats.sampleCount == 0) {
        Panel { Hint(stringResource(R.string.trip_empty)) }
        return
    }

    Panel {
        PanelTitle(stringResource(R.string.trip_title))
        InfoRow(stringResource(R.string.trip_duration), Format.duration(stats.durationMillis))
        InfoRow(stringResource(R.string.trip_distance), Format.value(stats.distanceKm, ObdUnit.KM, 2, units))
        InfoRow(stringResource(R.string.trip_max_speed), Format.value(stats.maxSpeedKmh, ObdUnit.KMH, 0, units))
        InfoRow(
            stringResource(R.string.trip_avg_speed),
            Format.value(stats.averageMovingSpeedKmh, ObdUnit.KMH, 0, units)
        )
        InfoRow(stringResource(R.string.trip_idle), Format.duration(stats.idleMillis))
        InfoRow(stringResource(R.string.trip_max_rpm), Format.value(stats.maxRpm, ObdUnit.RPM, 0, units))
        stats.maxCoolantC?.let {
            InfoRow(stringResource(R.string.trip_max_coolant), Format.value(it, ObdUnit.CELSIUS, 0, units))
        }
        val fuel = UnitConversion.litres(stats.fuelLitres, units)
        InfoRow(stringResource(R.string.trip_fuel), "${Format.number(fuel.value, 2)} ${fuel.unit}")
        stats.consumptionL100Km?.let {
            InfoRow(stringResource(R.string.trip_consumption), Format.value(it, ObdUnit.LITRE_PER_100KM, 1, units))
        }
        Hint(stringResource(R.string.trip_samples, stats.sampleCount))
    }

    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.exportTrip { result ->
                        exportError = !result.success
                        exportMessage = when {
                            !result.success -> failedText
                            result.inDownloads -> savedText
                            else -> sharedText
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.trip_export)) }
            OutlinedButton(
                onClick = {
                    trip.exportedUri?.let { uri ->
                        context.startActivity(Intent.createChooser(TripExport.shareIntent(uri), null))
                    }
                },
                enabled = trip.exportedUri != null,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.trip_share), color = TextBright) }
        }
        exportMessage?.let {
            Text(
                it,
                color = if (exportError) GaugeRed else GaugeGreen,
                style = MaterialTheme.typography.bodySmall
            )
        }
        OutlinedButton(onClick = viewModel::clearRecording, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.trip_clear), color = TextBright)
        }
    }
}

@Composable
private fun AccelerationSection(viewModel: ObdViewModel) {
    val connection by viewModel.connection.collectAsState()
    val performance by viewModel.performance.collectAsState()
    val units by viewModel.units.collectAsState()
    val profile by viewModel.profile.collectAsState()

    Panel {
        Hint(stringResource(R.string.perf_hint))
        if (connection !is ConnectionState.Connected) {
            Hint(stringResource(R.string.dash_offline))
        } else if (performance.measuring) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(GaugeGreen)
                Text(stringResource(R.string.perf_measuring), color = TextBright)
            }
        } else {
            Hint(stringResource(R.string.perf_waiting))
        }
        InfoRow(stringResource(R.string.perf_g_meter), "%.2f g".format(performance.currentG))
        performance.currentPowerHp?.let {
            InfoRow(stringResource(R.string.perf_power), "%.0f л.с.".format(it))
        }
        Hint(stringResource(R.string.perf_power_hint))
    }

    Panel {
        PanelTitle(stringResource(R.string.perf_runs))
        if (performance.runs.isEmpty()) {
            Hint(stringResource(R.string.perf_no_runs))
        } else {
            performance.runs.take(10).forEachIndexed { index, run ->
                RunCard(index + 1, run, units)
            }
        }
    }

    if (performance.brakings.isNotEmpty()) {
        Panel {
            PanelTitle(stringResource(R.string.perf_braking))
            performance.brakings.take(10).forEach { braking ->
                Text(
                    stringResource(
                        R.string.perf_braking_item,
                        braking.fromKmh,
                        braking.seconds,
                        braking.distanceM
                    ),
                    color = TextBright,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    if (performance.gearRatios.isNotEmpty()) {
        Panel {
            PanelTitle(stringResource(R.string.dash_gear))
            Hint(stringResource(R.string.dash_gear_learning, performance.learnedGears))
            performance.gearRatios.forEachIndexed { index, ratio ->
                // Отношение об/мин к км/ч само по себе мало о чём говорит, а вот
                // скорость на 2000 об/мин - понятная характеристика передачи.
                InfoRow("${index + 1}", "%.0f об/мин на 100 км/ч".format(ratio * 100))
            }
        }
    }

    OutlinedButton(onClick = viewModel::resetPerformance, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.perf_clear), color = TextBright)
    }
    Hint("${profile.name}: ${Format.number(profile.totalMassKg, 0)} кг")
}

@Composable
private fun RunCard(number: Int, run: AccelerationRun, units: com.obdscanner.core.obd.UnitSystem) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            stringResource(R.string.perf_run_title, number),
            style = MaterialTheme.typography.titleSmall,
            color = TextBright
        )
        run.splits.forEach { split ->
            // Скорость на финише есть только у замеров по дистанции. Копия в
            // локальную переменную нужна не для красоты: умное приведение типа
            // не работает для свойства из другого модуля (:core), и компилятор
            // такой код не пропустит.
            val trapSpeed = split.speedKmh
            val value = if (trapSpeed != null) {
                "%.2f с · %s".format(split.seconds, Format.value(trapSpeed, ObdUnit.KMH, 0, units))
            } else {
                "%.2f с".format(split.seconds)
            }
            InfoRow(split.kind.title, value)
        }
        InfoRow(stringResource(R.string.perf_max_speed), Format.value(run.maxSpeedKmh, ObdUnit.KMH, 0, units))
        InfoRow(stringResource(R.string.perf_distance), Format.value(run.distanceM / 1000.0, ObdUnit.KM, 3, units))
        InfoRow(stringResource(R.string.perf_peak_g), "%.2f g".format(run.peakAccelerationG))
        run.peakPowerHp?.let { power ->
            InfoRow(
                stringResource(R.string.perf_power),
                stringResource(R.string.perf_peak_power, power, run.peakPowerAtKmh ?: 0.0)
            )
        }
    }
}
