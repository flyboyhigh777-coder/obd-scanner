package com.obdscanner.app.ui

import android.content.Context
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.R
import com.obdscanner.app.transport.BluetoothDevices
import com.obdscanner.app.transport.ConnectionTarget
import com.obdscanner.app.transport.PairedDevice
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
 * Экран связи: выбор источника (Bluetooth / Wi-Fi / демо), подключение,
 * настройки опроса и терминал адаптера.
 */
@Composable
fun ConnectScreen(viewModel: ObdViewModel) {
    val context = LocalContext.current
    val connection by viewModel.connection.collectAsState()
    val units by viewModel.units.collectAsState()
    val pollInterval by viewModel.pollInterval.collectAsState()
    val terminal by viewModel.terminal.collectAsState()

    var source by remember { mutableStateOf(Source.BLUETOOTH) }
    var host by remember { mutableStateOf(viewModel.settings.wifiHost) }
    var port by remember { mutableStateOf(viewModel.settings.wifiPort.toString()) }
    var selected by remember { mutableStateOf(viewModel.settings.lastBluetoothAddress) }
    var paired by remember { mutableStateOf(emptyList<PairedDevice>()) }
    var hasPermission by remember { mutableStateOf(BluetoothDevices.hasPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) paired = BluetoothDevices.paired(context)
    }

    // Список сопряжённых устройств обновляем при входе на экран и при выдаче
    // разрешения: пользователь мог сопрячь адаптер, не закрывая приложение.
    LaunchedEffect(hasPermission) {
        if (hasPermission) paired = BluetoothDevices.paired(context)
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
            subtitle = connectionSubtitle(connection, context)
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
                        is ConnectionState.Failed -> stringResource(R.string.connect_state_disconnected)
                        ConnectionState.Disconnected -> stringResource(R.string.connect_state_disconnected)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = TextBright
                )
                if (connection is ConnectionState.Connecting) {
                    CircularProgressIndicator(Modifier.padding(start = 4.dp))
                }
            }
            (connection as? ConnectionState.Connecting)?.let { Hint(it.stage.title) }
            (connection as? ConnectionState.Failed)?.let { Text(it.message, color = GaugeRed) }
            (connection as? ConnectionState.Connected)?.let { state ->
                InfoRow(stringResource(R.string.connect_adapter), state.adapterId.ifEmpty { "ELM327" })
                InfoRow(
                    stringResource(R.string.connect_protocol),
                    state.protocol?.let { if (ru()) it.ru else it.en } ?: stringResource(R.string.common_dash)
                )
                InfoRow(stringResource(R.string.connect_pid_count), state.pidCount.toString())
            }
            if (connection is ConnectionState.Connected) {
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
                    Source.BLUETOOTH -> {
                        Hint(stringResource(R.string.connect_bluetooth_hint))
                        if (!hasPermission) {
                            PanelTitle(stringResource(R.string.connect_permission_title))
                            Hint(stringResource(R.string.connect_permission_text))
                            Button(
                                onClick = { permissionLauncher.launch(BluetoothDevices.permission) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.connect_permission_grant)) }
                        } else if (!BluetoothDevices.isEnabled(context)) {
                            Text(stringResource(R.string.connect_bluetooth_off), color = GaugeAmber)
                        } else if (paired.isEmpty()) {
                            Hint(stringResource(R.string.connect_no_paired))
                            OutlinedButton(
                                onClick = { paired = BluetoothDevices.paired(context) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.connect_refresh), color = TextBright) }
                        } else {
                            paired.forEach { device ->
                                SegmentButton(
                                    text = "${device.name}  ·  ${device.address}",
                                    selected = selected == device.address,
                                    modifier = Modifier.fillMaxWidth()
                                ) { selected = device.address }
                            }
                        }
                    }

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
                            Source.BLUETOOTH -> {
                                val address = selected
                                val device = paired.firstOrNull { it.address == address }
                                if (device != null) {
                                    viewModel.connect(ConnectionTarget.Bluetooth(device.address, device.name))
                                }
                            }
                        }
                    },
                    enabled = connection !is ConnectionState.Connecting &&
                        (source != Source.BLUETOOTH || paired.any { it.address == selected }),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.connect_button)) }
            }
        }

        Panel {
            PanelTitle(stringResource(R.string.connect_settings))
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

private fun connectionSubtitle(state: ConnectionState, context: Context): String? = when (state) {
    is ConnectionState.Connected -> when (val target = state.target) {
        is ConnectionTarget.Bluetooth -> target.name
        is ConnectionTarget.Wifi -> "${target.host}:${target.port}"
        ConnectionTarget.Demo -> context.getString(R.string.connect_source_demo)
    }
    else -> null
}

/** Язык интерфейса - для текстов, которые :core отдаёт парой ru/en. */
internal fun ru(): Boolean = com.obdscanner.core.elm.ObdText.russian
