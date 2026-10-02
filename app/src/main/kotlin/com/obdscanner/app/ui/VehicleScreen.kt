package com.obdscanner.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.R
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeGreen
import com.obdscanner.app.ui.theme.GradientTopBar
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.InfoRow
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.PanelTitle
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted
import com.obdscanner.core.obd.FuelType
import com.obdscanner.core.obd.ObdStandard
import com.obdscanner.core.obd.ObdUnit
import com.obdscanner.core.obd.Pids

/**
 * Что машина сама про себя рассказывает: VIN и всё, что из него выводится, имя
 * блока управления, протокол шины, счётчики после сброса ошибок.
 */
@Composable
fun VehicleScreen(viewModel: ObdViewModel) {
    val connection by viewModel.connection.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    val units by viewModel.units.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val dash = stringResource(R.string.common_dash)

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GradientTopBar(stringResource(R.string.vehicle_title))

        if (connection !is ConnectionState.Connected) {
            Panel { Hint(stringResource(R.string.dash_offline)) }
            return@Column
        }

        Panel {
            PanelTitle(stringResource(R.string.vehicle_vin))
            if (vehicle.loading) {
                CircularProgressIndicator()
            }
            val vin = vehicle.vin
            if (vin == null) {
                Hint(stringResource(R.string.vehicle_vin_unavailable))
            } else {
                Text(
                    vin,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextBright,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(vin))
                        copied = true
                    }) { Text(stringResource(R.string.vehicle_copy_vin), color = TextBright) }
                    if (copied) Text(stringResource(R.string.vehicle_copied), color = GaugeGreen)
                }
                vehicle.vinInfo?.let { info ->
                    InfoRow(stringResource(R.string.vehicle_country), info.country ?: dash)
                    InfoRow(stringResource(R.string.vehicle_manufacturer), info.manufacturer ?: dash)
                    InfoRow(stringResource(R.string.vehicle_year), info.modelYear?.toString() ?: dash)
                    InfoRow(stringResource(R.string.vehicle_serial), info.serial)
                    when (info.checkDigitValid) {
                        true -> Text(stringResource(R.string.vehicle_check_ok), color = GaugeGreen, style = MaterialTheme.typography.bodySmall)
                        false -> Text(stringResource(R.string.vehicle_check_bad), color = GaugeAmber, style = MaterialTheme.typography.bodySmall)
                        null -> Hint(stringResource(R.string.vehicle_check_na))
                    }
                    Hint(stringResource(R.string.vehicle_year_hint))
                }
            }
        }

        Panel {
            PanelTitle(stringResource(R.string.vehicle_ecu))
            InfoRow(stringResource(R.string.vehicle_ecu), vehicle.ecuName ?: dash)
            InfoRow(stringResource(R.string.vehicle_calibration), vehicle.calibrationId ?: dash)
            InfoRow(
                stringResource(R.string.vehicle_standard),
                vehicle.obdStandard?.let { ObdStandard.name(it) } ?: dash
            )
            InfoRow(
                stringResource(R.string.vehicle_fuel),
                vehicle.fuelType?.let { FuelType.name(it) } ?: dash
            )
            val state = connection as ConnectionState.Connected
            InfoRow(stringResource(R.string.vehicle_protocol), state.protocol?.let { if (ru()) it.ru else it.en } ?: dash)
            InfoRow(stringResource(R.string.vehicle_adapter), state.adapterId.ifEmpty { "ELM327" })
            InfoRow(
                stringResource(R.string.vehicle_voltage),
                vehicle.adapterVoltage?.let { Format.value(it, ObdUnit.VOLT, 1, units) } ?: dash
            )
        }

        if (vehicle.counters.isNotEmpty()) {
            Panel {
                PanelTitle(stringResource(R.string.vehicle_counters))
                vehicle.counters.forEach { (id, value) ->
                    val pid = Pids.byId[id] ?: return@forEach
                    InfoRow(pid.title, Format.value(pid, value, units))
                }
            }
        }

        OutlinedButton(onClick = viewModel::refreshVehicle, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.vehicle_refresh), color = TextBright)
        }
        if (vehicle.loaded && vehicle.vin == null && vehicle.ecuName == null) {
            Hint(stringResource(R.string.vehicle_unknown), Modifier.padding(top = 2.dp))
        }
        Text(
            stringResource(R.string.app_version, com.obdscanner.app.BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
    }
}
