package com.obdscanner.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.R
import com.obdscanner.app.transport.BluetoothScanner
import com.obdscanner.app.transport.ConnectionTarget
import com.obdscanner.app.transport.DiscoveredDevice
import com.obdscanner.app.transport.LinkKind
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
import com.obdscanner.app.ui.theme.TextDim
import com.obdscanner.core.obd.UnitSystem

private enum class Source { BLUETOOTH, WIFI, DEMO }

/**
 * Экран связи: выбор адаптера (классический Bluetooth, BLE, Wi-Fi или демо),
 * подключение, настройки опроса и терминал.
 *
 * Два списка устройств - не прихоть: классический ELM327 виден только среди
 * сопряжённых, а BLE-адаптер только в результатах поиска, и человеку надо видеть
 * оба, не разбираясь в различиях Bluetooth.
 */
@Composable
fun ConnectScreen(viewModel: ObdViewModel) {
    val context = LocalContext.current
    val connection by viewModel.connection.collectAsState()
    val units by viewModel.units.collectAsState()
    val pollInterval by viewModel.pollInterval.collectAsState()
    val terminal by viewModel.terminal.collectAsState()
    val paired by viewModel.pairedDevices.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val autoReconnect by viewModel.autoReconnect.collectAsState()
    val gaugeStyle by viewModel.gaugeStyle.collectAsState()

    var source by remember { mutableStateOf(Source.BLUETOOTH) }
    var host by remember { mutableStateOf(viewModel.settings.wifiHost) }
    var port by remember { mutableStateOf(viewModel.settings.wifiPort.toString()) }
    var selected by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var hasConnectPermission by remember {
        mutableStateOf(BluetoothScanner.hasPermissions(context, BluetoothScanner.connectPermissions))
    }
    var scanDenied by remember { mutableStateOf(false) }

    val connectPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasConnectPermission = result.values.all { it } || BluetoothScanner.connectPermissions.isEmpty()
        if (hasConnectPermission) viewModel.refreshPairedDevices()
    }
    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            scanDenied = false
            viewModel.startBleScan()
        } else {
            scanDenied = true
        }
    }

    LaunchedEffect(hasConnectPermission) {
        if (hasConnectPermission) viewModel.refreshPairedDevices()
    }
    // Уходя с экрана, сканирование надо выключить: оно ест батарею и больше не нужно.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopBleScan() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GradientTopBar(
            title = stringResource(R.string.connect_title),
            subtitle = (connection as? ConnectionState.Connected)?.target?.title
        )

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(
                    when (connection) {
                        is ConnectionState.Connected -> GaugeGreen
                        is ConnectionState.Connecting -> GaugeAmber
                        is ConnectionState.Failed -> GaugeRed
                        ConnectionState.Disconnected -> TextDim
                    }
                )
                Text(
                    when (connection) {
                        is ConnectionState.Connected -> stringResource(R.string.connect_state_connected)
                        is ConnectionState.Connecting -> stringResource(R.string.connect_connecting)
                        else -> stringResource(R.string.connect_state_disconnected)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = TextBright
                )
                if (connection is ConnectionState.Connecting) {
                    CircularProgressIndicator(Modifier.padding(start = 4.dp))
                }
            }
            (connection as? ConnectionState.Connecting)?.let { state ->
                Hint(state.stage.title)
                if (state.attempt > 0) {
                    Text(stringResource(R.string.connect_reconnecting, state.attempt), color = GaugeAmber)
                }
            }
            (connection as? ConnectionState.Failed)?.let { Text(it.message, color = GaugeRed) }
            (connection as? ConnectionState.Connected)?.let { state ->
                InfoRow(stringResource(R.string.connect_adapter), state.adapterId.ifEmpty { "ELM327" })
                InfoRow(
                    stringResource(R.string.connect_protocol),
                    state.protocol?.let { if (ru()) it.ru else it.en } ?: stringResource(R.string.common_dash)
                )
                InfoRow(stringResource(R.string.connect_pid_count), state.pidCount.toString())
                OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.connect_disconnect), color = TextBright)
                }
            }
        }

        if (connection !is ConnectionState.Connected) {
            Panel {
                Hint(stringResource(R.string.connect_intro))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegmentButton(
                        stringResource(R.string.connect_source_bluetooth),
                        source == Source.BLUETOOTH,
                        Modifier.weight(1f)
                    ) { source = Source.BLUETOOTH }
                    SegmentButton(
                        stringResource(R.string.connect_source_wifi),
                        source == Source.WIFI,
                        Modifier.weight(1f)
                    ) { source = Source.WIFI }
                    SegmentButton(
                        stringResource(R.string.connect_source_demo),
                        source == Source.DEMO,
                        Modifier.weight(1f)
                    ) { source = Source.DEMO }
                }

                when (source) {
                    Source.BLUETOOTH -> BluetoothSection(
                        paired = paired,
                        discovered = discovered,
                        scanning = scanning,
                        selected = selected,
                        hasConnectPermission = hasConnectPermission,
                        bluetoothOn = BluetoothScanner.isEnabled(context),
                        bleSupported = BluetoothScanner.supportsBle(context),
                        scanDenied = scanDenied,
                        onSelect = { selected = it },
                        onGrantConnect = {
                            connectPermissionLauncher.launch(BluetoothScanner.connectPermissions.toTypedArray())
                        },
                        onRefresh = viewModel::refreshPairedDevices,
                        onScan = {
                            if (BluetoothScanner.hasPermissions(context, BluetoothScanner.scanPermissions)) {
                                viewModel.startBleScan()
                            } else {
                                scanPermissionLauncher.launch(BluetoothScanner.scanPermissions.toTypedArray())
                            }
                        },
                        onStopScan = viewModel::stopBleScan
                    )

                    Source.WIFI -> {
                        Hint(stringResource(R.string.connect_wifi_hint))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = { host = it.trim() },
                                label = { Text(stringResource(R.string.connect_host)) },
                                singleLine = true,
                                modifier = Modifier.weight(2f)
                            )
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                                label = { Text(stringResource(R.string.connect_port)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Source.DEMO -> Hint(stringResource(R.string.connect_demo_hint))
                }

                Button(
                    onClick = {
                        when (source) {
                            Source.DEMO -> viewModel.connect(ConnectionTarget.Demo)
                            Source.WIFI -> {
                                val portNumber = port.toIntOrNull() ?: 35000
                                viewModel.settings.wifiHost = host
                                viewModel.settings.wifiPort = portNumber
                                viewModel.connect(ConnectionTarget.Wifi(host, portNumber))
                            }

                            Source.BLUETOOTH -> selected?.let { device ->
                                viewModel.stopBleScan()
                                viewModel.connect(
                                    if (device.kind == LinkKind.BLE) {
                                        ConnectionTarget.BluetoothLe(device.address, device.name)
                                    } else {
                                        ConnectionTarget.Bluetooth(device.address, device.name)
                                    }
                                )
                            }
                        }
                    },
                    enabled = connection !is ConnectionState.Connecting &&
                        (source != Source.BLUETOOTH || selected != null),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.connect_button)) }
            }
        }

        Panel {
            PanelTitle(stringResource(R.string.connect_settings))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoReconnect, onCheckedChange = viewModel::setAutoReconnect)
                Text(
                    stringResource(R.string.connect_auto_reconnect),
                    color = TextBright,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Hint(stringResource(R.string.connect_auto_reconnect_hint))

            Text(stringResource(R.string.connect_gauge_style), color = TextBright)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentButton(stringResource(R.string.gauge_style_dial), gaugeStyle == "DIAL", Modifier.weight(1f)) {
                    viewModel.setGaugeStyle("DIAL")
                }
                SegmentButton(stringResource(R.string.gauge_style_bar), gaugeStyle == "BAR", Modifier.weight(1f)) {
                    viewModel.setGaugeStyle("BAR")
                }
                SegmentButton(
                    stringResource(R.string.gauge_style_digital),
                    gaugeStyle == "DIGITAL",
                    Modifier.weight(1f)
                ) { viewModel.setGaugeStyle("DIGITAL") }
            }

            Text(stringResource(R.string.connect_poll_interval, pollInterval), color = TextBright)
            Slider(
                value = pollInterval.toFloat(),
                onValueChange = { viewModel.setPollInterval(it.toInt()) },
                valueRange = 100f..1500f,
                steps = 13
            )
            Hint(stringResource(R.string.connect_poll_hint))

            Text(stringResource(R.string.connect_units), color = TextBright)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentButton(
                    stringResource(R.string.connect_units_metric),
                    units == UnitSystem.METRIC,
                    Modifier.weight(1f)
                ) { viewModel.setUnits(UnitSystem.METRIC) }
                SegmentButton(
                    stringResource(R.string.connect_units_imperial),
                    units == UnitSystem.IMPERIAL,
                    Modifier.weight(1f)
                ) { viewModel.setUnits(UnitSystem.IMPERIAL) }
            }
        }

        if (connection is ConnectionState.Connected) {
            TerminalPanel(terminal, viewModel::sendTerminalCommand)
        }
    }
}

/** Списки адаптеров: сопряжённые и найденные поиском. */
@Composable
private fun BluetoothSection(
    paired: List<DiscoveredDevice>,
    discovered: List<DiscoveredDevice>,
    scanning: Boolean,
    selected: DiscoveredDevice?,
    hasConnectPermission: Boolean,
    bluetoothOn: Boolean,
    bleSupported: Boolean,
    scanDenied: Boolean,
    onSelect: (DiscoveredDevice) -> Unit,
    onGrantConnect: () -> Unit,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit
) {
    Hint(stringResource(R.string.connect_bluetooth_hint))
    if (!hasConnectPermission) {
        PanelTitle(stringResource(R.string.connect_permission_title))
        Hint(stringResource(R.string.connect_permission_text))
        Button(onClick = onGrantConnect, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connect_permission_grant))
        }
        return
    }
    if (!bluetoothOn) {
        Text(stringResource(R.string.connect_bluetooth_off), color = GaugeAmber)
        return
    }

    PanelTitle(stringResource(R.string.connect_paired))
    if (paired.isEmpty()) {
        Hint(stringResource(R.string.connect_no_paired))
    } else {
        paired.forEach { device -> DeviceRow(device, selected?.address == device.address, onSelect) }
    }
    OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.connect_refresh), color = TextBright)
    }

    PanelTitle(stringResource(R.string.connect_found))
    Hint(stringResource(R.string.connect_ble_hint))
    if (!bleSupported) {
        Text(stringResource(R.string.connect_no_ble), color = GaugeAmber)
        return
    }
    if (scanDenied) {
        Text(stringResource(R.string.connect_location_title), color = GaugeAmber)
        Hint(stringResource(R.string.connect_location_text))
    }
    discovered.forEach { device -> DeviceRow(device, selected?.address == device.address, onSelect) }
    if (scanning) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.connect_ble_scanning), color = TextBright)
        }
        OutlinedButton(onClick = onStopScan, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connect_ble_stop), color = TextBright)
        }
    } else {
        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connect_ble_scan), color = TextBright)
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, selected: Boolean, onSelect: (DiscoveredDevice) -> Unit) {
    val kind = stringResource(if (device.kind == LinkKind.BLE) R.string.link_ble else R.string.link_classic)
    val signal = device.rssi?.let { ", $it dBm" }.orEmpty()
    SegmentButton(
        text = "${device.name} · $kind$signal",
        selected = selected,
        modifier = Modifier.fillMaxWidth()
    ) { onSelect(device) }
}

@Composable
private fun TerminalPanel(lines: List<String>, onSend: (String) -> Unit) {
    var command by remember { mutableStateOf("") }
    Panel {
        PanelTitle(stringResource(R.string.connect_terminal))
        Hint(stringResource(R.string.connect_terminal_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text(stringResource(R.string.connect_terminal_command)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                onSend(command)
                command = ""
            }) { Text(stringResource(R.string.connect_terminal_send)) }
        }
        lines.takeLast(12).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = TextDim)
        }
    }
}

/** Язык интерфейса - для текстов, которые :core отдаёт парой ru/en. */
internal fun ru(): Boolean = com.obdscanner.core.elm.ObdText.russian
