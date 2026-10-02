package com.obdscanner.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.R
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeBlue
import com.obdscanner.app.ui.theme.GaugeCyan
import com.obdscanner.app.ui.theme.GaugeGreen
import com.obdscanner.app.ui.theme.GaugeRed
import com.obdscanner.app.ui.theme.GradientTopBar
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.StatusDot
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.core.obd.ObdPid
import com.obdscanner.core.obd.ObdUnit
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.obd.UnitConversion
import com.obdscanner.core.obd.UnitSystem
import com.obdscanner.core.vehicle.VehicleProfile

/**
 * Приборы: обороты и скорость крупно, остальное плитками. Вид (шкалы, полоски
 * или просто цифры) выбирается на вкладке связи - на ходу у всех разные
 * предпочтения, а менять это в коде каждому не предложишь.
 *
 * Пределы шкал берутся из профиля машины: у Патриота красная зона около 4800,
 * и шкала до 8000 оставляла бы треть прибора мёртвой.
 */
@Composable
fun DashboardScreen(viewModel: ObdViewModel) {
    val connection by viewModel.connection.collectAsState()
    val live by viewModel.live.collectAsState()
    val units by viewModel.units.collectAsState()
    val supported by viewModel.supportedPids.collectAsState()
    val selected by viewModel.dashboardPids.collectAsState()
    val style by viewModel.gaugeStyle.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val performance by viewModel.performance.collectAsState()
    var chooser by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GradientTopBar(stringResource(R.string.dash_title), subtitle = profile.name)

        if (connection !is ConnectionState.Connected) {
            Panel { Hint(stringResource(R.string.dash_offline)) }
            return@Column
        }

        live.monitor?.let { monitor ->
            Panel {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusDot(if (monitor.milOn) GaugeRed else GaugeGreen)
                    Text(
                        stringResource(if (monitor.milOn) R.string.dash_mil_on else R.string.dash_mil_off),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (monitor.milOn) GaugeRed else TextBright
                    )
                }
                if (monitor.dtcCount > 0) Hint(stringResource(R.string.dash_dtc_count, monitor.dtcCount))
            }
        }

        if (!live.hasData) Panel { Hint(stringResource(R.string.dash_waiting)) }

        val dash = stringResource(R.string.common_dash)
        val rpm = live.values[Pids.RPM.id]
        val speed = live.values[Pids.SPEED.id]

        // Обороты и скорость - отдельно и крупно: это то, на что смотрят на ходу.
        if (style == "DIGITAL") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ValueTile(
                    Pids.RPM.title,
                    rpm?.let { Format.value(Pids.RPM, it, units) } ?: dash,
                    Modifier.weight(1f)
                )
                ValueTile(
                    Pids.SPEED.title,
                    speed?.let { Format.value(Pids.SPEED, it, units) } ?: dash,
                    Modifier.weight(1f)
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MainGauge(Pids.RPM, rpm, units, profile, rpmAccent(rpm, profile), style, Modifier.weight(1f))
                MainGauge(Pids.SPEED, speed, units, profile, GaugeBlue, style, Modifier.weight(1f))
            }
        }

        // Передача, запас хода и расход - то, чего нет ни в одном PID, но что
        // считается из них и профиля машины.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ValueTile(
                stringResource(R.string.dash_gear),
                live.gear?.toString() ?: dash,
                Modifier.weight(1f)
            )
            ValueTile(
                stringResource(R.string.dash_range),
                live.rangeKm?.let { Format.value(it, ObdUnit.KM, 0, units) } ?: dash,
                Modifier.weight(1f)
            )
        }
        if (performance.learnedGears in 1..7 && live.gear == null) {
            Hint(stringResource(R.string.dash_gear_learning, performance.learnedGears))
        }

        consumptionText(live.litresPer100Km, live.litresPerHour, units)?.let {
            ValueTile(stringResource(R.string.dash_consumption), it, Modifier.fillMaxWidth())
        }

        // Остальные выбранные параметры - по две плитки в ряд: больше на телефоне
        // не влезает так, чтобы цифры читались на ходу.
        selected
            .filter { it != Pids.RPM.id && it != Pids.SPEED.id }
            .mapNotNull { Pids.byId[it] }
            .chunked(2)
            .forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { pid ->
                        val value = live.values[pid.id]
                        if (style == "BAR") {
                            BarGauge(
                                title = pid.title,
                                valueText = value?.let { Format.value(pid, it, units) } ?: dash,
                                fraction = value?.let { fractionFor(pid, it, units, profile) },
                                accent = alertColor(alertFor(pid, value)),
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            ValueTile(
                                title = pid.title,
                                valueText = value?.let { Format.value(pid, it, units) } ?: dash,
                                modifier = Modifier.weight(1f),
                                alert = alertFor(pid, value)
                            )
                        }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }

        // Свои параметры показываются отдельным блоком: их добавлял человек, и
        // путать их со стандартными не надо.
        if (live.custom.isNotEmpty()) {
            Panel {
                Text(stringResource(R.string.dash_custom), color = TextBright)
                live.custom.forEach { (name, value) ->
                    com.obdscanner.app.ui.theme.InfoRow(name, "%.2f".format(value))
                }
            }
        }

        OutlinedButton(onClick = { chooser = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dash_choose), color = TextBright)
        }
    }

    if (chooser) {
        AlertDialog(
            onDismissRequest = { chooser = false },
            confirmButton = {
                TextButton(onClick = { chooser = false }) { Text(stringResource(R.string.dash_close)) }
            },
            title = { Text(stringResource(R.string.dash_choose_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Hint(stringResource(R.string.dash_choose_hint), Modifier.padding(bottom = 6.dp))
                    supported.filter { it.id !in Pids.STATIC && it.unit != ObdUnit.NONE }.forEach { pid ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = pid.id in selected,
                                onCheckedChange = { viewModel.toggleDashboardPid(pid.id) }
                            )
                            Text(pid.title, color = TextBright, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun MainGauge(
    pid: ObdPid,
    value: Double?,
    units: UnitSystem,
    profile: VehicleProfile,
    accent: Color,
    style: String,
    modifier: Modifier
) {
    val fraction = value?.let { fractionFor(pid, it, units, profile) }
    val text = value?.let { Format.number(pid, it, units) } ?: "—"
    if (style == "BAR") {
        BarGauge(pid.title, "$text ${Format.unit(pid, units)}", fraction, accent, modifier)
    } else {
        ArcGauge(pid.title, text, Format.unit(pid, units), fraction, accent, modifier)
    }
}

/**
 * Доля заполнения шкалы. Для оборотов и скорости предел берётся из профиля
 * машины, для остального - из описания PID.
 */
private fun fractionFor(pid: ObdPid, value: Double, units: UnitSystem, profile: VehicleProfile): Float? {
    val limit = when (pid.id) {
        Pids.RPM.id -> profile.rpmRedline
        Pids.SPEED.id -> profile.maxSpeedKmh
        Pids.MAF.id -> profile.maxMafGramsPerSecond
        else -> null
    }
    val range = if (limit != null) {
        val min = UnitConversion.convert(0.0, pid.unit, units, pid.decimals).value
        val max = UnitConversion.convert(limit, pid.unit, units, pid.decimals).value
        min..max
    } else {
        Format.range(pid, units)
    }
    val converted = UnitConversion.convert(value, pid.unit, units, pid.decimals).value
    val span = range.endInclusive - range.start
    if (span <= 0.0) return null
    return ((converted - range.start) / span).toFloat()
}

/**
 * Расход показываем на сотню, только когда машина едет: на стоянке литры на
 * 100 км не определены, и там честнее литры в час.
 */
private fun consumptionText(litresPer100Km: Double?, litresPerHour: Double?, units: UnitSystem): String? = when {
    litresPer100Km != null -> Format.value(litresPer100Km, ObdUnit.LITRE_PER_100KM, 1, units)
    litresPerHour != null -> Format.value(litresPerHour, ObdUnit.LITRE_PER_HOUR, 2, units)
    else -> null
}

private fun alertColor(alert: Alert): Color = when (alert) {
    Alert.NONE -> GaugeCyan
    Alert.WARNING -> GaugeAmber
    Alert.DANGER -> GaugeRed
}

/**
 * Когда подкрашивать значение. Пороги - бытовые, не из стандарта: 110 °C для
 * охлаждающей жидкости это уже кипение в пробке, а 11.5 В на работающем
 * двигателе означает, что генератор не заряжает.
 */
private fun alertFor(pid: ObdPid, value: Double?): Alert {
    if (value == null) return Alert.NONE
    return when (pid.id) {
        Pids.COOLANT_TEMP.id -> when {
            value >= 110 -> Alert.DANGER
            value >= 102 -> Alert.WARNING
            else -> Alert.NONE
        }

        Pids.OIL_TEMP.id -> if (value >= 130) Alert.WARNING else Alert.NONE
        Pids.CONTROL_MODULE_VOLTAGE.id -> when {
            value < 11.5 || value > 15.2 -> Alert.DANGER
            value < 12.2 || value > 14.8 -> Alert.WARNING
            else -> Alert.NONE
        }

        Pids.CATALYST_TEMP_B1S1.id -> if (value >= 850) Alert.WARNING else Alert.NONE
        Pids.SHORT_FUEL_TRIM_1.id, Pids.LONG_FUEL_TRIM_1.id,
        Pids.SHORT_FUEL_TRIM_2.id, Pids.LONG_FUEL_TRIM_2.id ->
            // Коррекция больше 20% означает, что ЭБУ уже на пределе компенсации
            // подсоса или переобогащения - дальше пойдут ошибки P0171/P0172.
            if (value <= -20 || value >= 20) Alert.WARNING else Alert.NONE

        else -> Alert.NONE
    }
}

/** Шкала оборотов желтеет и краснеет у красной зоны, которая взята из профиля. */
private fun rpmAccent(rpm: Double?, profile: VehicleProfile): Color = when {
    rpm == null -> GaugeCyan
    rpm > profile.rpmRedline * 0.95 -> GaugeRed
    rpm > profile.rpmRedline * 0.82 -> GaugeAmber
    else -> GaugeCyan
}
