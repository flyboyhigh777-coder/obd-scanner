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
import com.obdscanner.app.ui.theme.StatusDot
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.core.obd.ObdUnit
import com.obdscanner.core.obd.UnitConversion

/**
 * Поездка: запись параметров с привязкой ко времени, итоги и выгрузка в CSV.
 * Запись живёт и после отключения адаптера - итоги можно посмотреть уже дома.
 */
@Composable
fun TripScreen(viewModel: ObdViewModel) {
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

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GradientTopBar(stringResource(R.string.trip_title))

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
                if (connection !is ConnectionState.Connected) {
                    Hint(stringResource(R.string.dash_offline))
                }
            }
        }

        if (stats.sampleCount == 0) {
            Panel { Hint(stringResource(R.string.trip_empty)) }
            return@Column
        }

        Panel {
            PanelTitle(stringResource(R.string.trip_title))
            InfoRow(stringResource(R.string.trip_duration), Format.duration(stats.durationMillis))
            InfoRow(
                stringResource(R.string.trip_distance),
                Format.value(stats.distanceKm, ObdUnit.KM, 2, units)
            )
            InfoRow(
                stringResource(R.string.trip_max_speed),
                Format.value(stats.maxSpeedKmh, ObdUnit.KMH, 0, units)
            )
            InfoRow(
                stringResource(R.string.trip_avg_speed),
                Format.value(stats.averageMovingSpeedKmh, ObdUnit.KMH, 0, units)
            )
            InfoRow(stringResource(R.string.trip_idle), Format.duration(stats.idleMillis))
            InfoRow(
                stringResource(R.string.trip_max_rpm),
                Format.value(stats.maxRpm, ObdUnit.RPM, 0, units)
            )
            stats.maxCoolantC?.let {
                InfoRow(
                    stringResource(R.string.trip_max_coolant),
                    Format.value(it, ObdUnit.CELSIUS, 0, units)
                )
            }
            val fuel = UnitConversion.litres(stats.fuelLitres, units)
            InfoRow(
                stringResource(R.string.trip_fuel),
                "${Format.number(fuel.value, 2)} ${fuel.unit}"
            )
            stats.consumptionL100Km?.let {
                InfoRow(
                    stringResource(R.string.trip_consumption),
                    Format.value(it, ObdUnit.LITRE_PER_100KM, 1, units)
                )
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
                            context.startActivity(
                                Intent.createChooser(TripExport.shareIntent(uri), null)
                            )
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
}
