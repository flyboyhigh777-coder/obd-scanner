package com.obdscanner.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.R
import com.obdscanner.app.data.SaeDtcCatalogAsset
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeGreen
import com.obdscanner.app.ui.theme.GradientTopBar
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.InfoRow
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.PanelTitle
import com.obdscanner.app.ui.theme.SegmentButton
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted
import com.obdscanner.core.dtc.ManufacturerDtc
import com.obdscanner.core.obd.CustomPid
import com.obdscanner.core.obd.FuelType
import com.obdscanner.core.obd.ObdStandard
import com.obdscanner.core.obd.ObdUnit
import com.obdscanner.core.obd.Pids

/**
 * Экран «Авто»: что машина рассказала о себе (VIN, блок управления, счётчики) и
 * что о ней рассказали мы - профиль с объёмом бака, массой и шинами, от которых
 * зависят расчёты, плюс свои параметры (PID).
 */
@Composable
fun VehicleScreen(viewModel: ObdViewModel) {
    val connection by viewModel.connection.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    val units by viewModel.units.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val customPids by viewModel.customPids.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var addingPid by remember { mutableStateOf(false) }
    val dash = stringResource(R.string.common_dash)

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GradientTopBar(stringResource(R.string.vehicle_title), subtitle = profile.name)

        // --- профиль доступен и без связи с машиной: его как раз и настраивают заранее
        Panel {
            PanelTitle(stringResource(R.string.vehicle_profile))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.availableProfiles.forEach { candidate ->
                    SegmentButton(candidate.name, profile.id == candidate.id, Modifier.weight(1f)) {
                        viewModel.selectProfile(candidate.id)
                    }
                }
            }
            Hint(profile.notes)
            InfoRow(stringResource(R.string.vehicle_engine), profile.engine)
            InfoRow(stringResource(R.string.vehicle_ecu), profile.ecuName)
            ProfileEditor(viewModel)
            val correction = (profile.speedCorrection - 1.0) * 100
            InfoRow(
                stringResource(R.string.vehicle_speed_correction, "%+.1f %%".format(correction)),
                profile.tyre.toString()
            )
            profile.manufacturerDtcFamily?.let { family ->
                Hint(stringResource(R.string.vehicle_ecu_table, family, ManufacturerDtc.size(family)))
            }
            if (SaeDtcCatalogAsset.loaded) {
                Hint(stringResource(R.string.vehicle_sae_table, SaeDtcCatalogAsset.size))
            }
        }

        // --- свои параметры: тоже не требуют связи, их заводят заранее
        Panel {
            PanelTitle(stringResource(R.string.custom_title))
            Hint(stringResource(R.string.custom_hint))
            if (customPids.isEmpty()) {
                Hint(stringResource(R.string.custom_empty))
            } else {
                customPids.forEach { pid ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pid.name, color = TextBright, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${pid.request} · ${pid.formulaText}",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = { viewModel.removeCustomPid(pid.name) }) {
                            Text(stringResource(R.string.custom_delete), color = GaugeAmber)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { addingPid = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.custom_add), color = TextBright)
            }
        }

        if (connection !is ConnectionState.Connected) {
            Panel { Hint(stringResource(R.string.dash_offline)) }
            return@Column
        }

        Panel {
            PanelTitle(stringResource(R.string.vehicle_vin))
            if (vehicle.loading) CircularProgressIndicator()
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        true -> Text(
                            stringResource(R.string.vehicle_check_ok),
                            color = GaugeGreen,
                            style = MaterialTheme.typography.bodySmall
                        )

                        false -> Text(
                            stringResource(R.string.vehicle_check_bad),
                            color = GaugeAmber,
                            style = MaterialTheme.typography.bodySmall
                        )

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
            InfoRow(stringResource(R.string.vehicle_fuel), vehicle.fuelType?.let { FuelType.name(it) } ?: dash)
            val state = connection as ConnectionState.Connected
            InfoRow(
                stringResource(R.string.vehicle_protocol),
                state.protocol?.let { if (ru()) it.ru else it.en } ?: dash
            )
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
        Text(
            stringResource(R.string.app_version, com.obdscanner.app.BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
    }

    if (addingPid) {
        CustomPidDialog(
            onDismiss = { addingPid = false },
            onSave = { pid ->
                viewModel.addCustomPid(pid)
                addingPid = false
            }
        )
    }
}

/** Поля профиля, которые человек может знать про свою машину. */
@Composable
private fun ProfileEditor(viewModel: ObdViewModel) {
    val profile by viewModel.profile.collectAsState()
    var tyreText by remember(profile.id) { mutableStateOf(profile.tyre.toString()) }
    val tyreValid = viewModel.parseTyre(tyreText) != null

    Hint(stringResource(R.string.vehicle_profile_hint))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(
            label = stringResource(R.string.vehicle_tank),
            value = profile.tankLitres,
            decimals = 0,
            modifier = Modifier.weight(1f)
        ) { viewModel.updateProfile(tankLitres = it) }
        NumberField(
            label = stringResource(R.string.vehicle_redline),
            value = profile.rpmRedline,
            decimals = 0,
            modifier = Modifier.weight(1f)
        ) { viewModel.updateProfile(rpmRedline = it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(
            label = stringResource(R.string.vehicle_mass),
            value = profile.curbMassKg,
            decimals = 0,
            modifier = Modifier.weight(1f)
        ) { viewModel.updateProfile(curbMassKg = it) }
        NumberField(
            label = stringResource(R.string.vehicle_payload),
            value = profile.payloadKg,
            decimals = 0,
            modifier = Modifier.weight(1f)
        ) { viewModel.updateProfile(payloadKg = it) }
    }
    OutlinedTextField(
        value = tyreText,
        onValueChange = { text ->
            tyreText = text
            if (viewModel.parseTyre(text) != null) viewModel.updateProfile(tyreText = text)
        },
        label = { Text(stringResource(R.string.vehicle_tyre)) },
        singleLine = true,
        isError = !tyreValid,
        modifier = Modifier.fillMaxWidth()
    )
    if (!tyreValid) Text(stringResource(R.string.vehicle_tyre_bad), color = GaugeAmber)
    OutlinedButton(
        onClick = {
            viewModel.selectProfile(profile.id)
            tyreText = viewModel.profile.value.tyre.toString()
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.vehicle_profile_reset), color = TextBright) }
}

/** Поле ввода числа, которое не даёт ввести мусор и не теряет ввод на каждом знаке. */
@Composable
private fun NumberField(
    label: String,
    value: Double,
    decimals: Int,
    modifier: Modifier = Modifier,
    onChange: (Double) -> Unit
) {
    var text by remember(value) { mutableStateOf(Format.number(value, decimals)) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val cleaned = input.filter { it.isDigit() || it == '.' || it == ',' }
            text = cleaned
            cleaned.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

/** Добавление своего параметра: номер PID и формула. */
@Composable
private fun CustomPidDialog(onDismiss: () -> Unit, onSave: (CustomPid) -> Unit) {
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(0x01) }
    var pidHex by remember { mutableStateOf("") }
    var bytes by remember { mutableStateOf("2") }
    var formula by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }

    val candidate = CustomPid(
        name = name.trim().ifEmpty { "PID" },
        mode = mode,
        pid = pidHex.trim().toIntOrNull(16) ?: -1,
        dataBytes = bytes.toIntOrNull() ?: 0,
        formulaText = formula.trim(),
        unitLabel = unit.trim()
    )
    val valid = name.isNotBlank() && candidate.pid >= 0 && candidate.valid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_add)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.custom_name)) },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegmentButton("01", mode == 0x01, Modifier.weight(1f)) { mode = 0x01 }
                    SegmentButton("22", mode == 0x22, Modifier.weight(1f)) { mode = 0x22 }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pidHex,
                        onValueChange = { pidHex = it.filter { c -> c.isDigit() || c.uppercaseChar() in 'A'..'F' }.take(4) },
                        label = { Text(stringResource(R.string.custom_pid)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = bytes,
                        onValueChange = { bytes = it.filter { c -> c.isDigit() }.take(1) },
                        label = { Text(stringResource(R.string.custom_bytes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = formula,
                    onValueChange = { formula = it },
                    label = { Text(stringResource(R.string.custom_formula)) },
                    singleLine = true
                )
                Hint(stringResource(R.string.custom_formula_help))
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text(stringResource(R.string.custom_unit)) },
                    singleLine = true
                )
                if (!valid) Text(stringResource(R.string.custom_invalid), color = GaugeAmber)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(candidate) }, enabled = valid) {
                Text(stringResource(R.string.custom_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dtc_cancel)) }
        }
    )
}
