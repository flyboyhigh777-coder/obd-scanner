package com.obdscanner.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.R
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeGreen
import com.obdscanner.app.ui.theme.GaugeRed
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.InfoRow
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.PanelTitle
import com.obdscanner.app.ui.theme.StatusDot
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted
import com.obdscanner.core.electrical.Battery
import com.obdscanner.core.electrical.BatteryVerdict
import com.obdscanner.core.electrical.CrankVerdict
import com.obdscanner.core.electrical.LeakVerdict
import java.util.Locale

/**
 * Батарея и генератор.
 *
 * Про ток здесь сказано прямо в интерфейсе, а не только в коде: его нельзя
 * получить через OBD-II, и человек должен узнать это от приложения, а не
 * после получаса поисков несуществующей кнопки.
 */
@Composable
fun BatterySection(viewModel: ObdViewModel) {
    val connection by viewModel.connection.collectAsState()
    val battery by viewModel.battery.collectAsState()

    if (connection !is ConnectionState.Connected) {
        Panel { Hint(stringResource(R.string.dash_offline)) }
        return
    }

    Panel {
        val volts = battery.volts
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(verdictColor(battery.verdict))
            Text(
                volts?.let { "%.1f В".format(Locale.ROOT, it) } ?: "—",
                style = MaterialTheme.typography.displaySmall,
                color = TextBright,
                fontWeight = FontWeight.Bold
            )
        }
        Text(battery.verdict.title, color = verdictColor(battery.verdict))
        Hint(
            stringResource(
                if (battery.fromAdapter) R.string.battery_source_adapter else R.string.battery_source_ecu
            )
        )

        battery.chargePercent?.let { percent ->
            InfoRow(stringResource(R.string.battery_charge), "$percent %")
            Hint(stringResource(R.string.battery_charge_hint))
        }
        battery.minVolts?.let { InfoRow(stringResource(R.string.battery_min), "%.1f В".format(Locale.ROOT, it)) }
        battery.maxVolts?.let { InfoRow(stringResource(R.string.battery_max), "%.1f В".format(Locale.ROOT, it)) }

        if (battery.history.size >= 2) VoltageChart(battery.history)

        OutlinedButton(onClick = viewModel::resetBattery, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.battery_reset))
        }
    }

    // Поправка к показаниям адаптера. Без неё спорить с цифрой бессмысленно:
    // дешёвый ELM327 легко врёт на полвольта, а между "генератор не заряжает"
    // и "всё в порядке" разница как раз в эту сторону.
    Panel {
        PanelTitle(stringResource(R.string.battery_calibration_title))
        Hint(stringResource(R.string.battery_calibration_hint))
        NumberField(
            label = stringResource(R.string.battery_calibration),
            value = viewModel.voltageCalibration,
            decimals = 2,
            modifier = Modifier.fillMaxWidth()
        ) { viewModel.updateVoltageOffset(it) }
    }

    Panel {
        PanelTitle(stringResource(R.string.battery_crank_title))
        Hint(stringResource(R.string.battery_crank_hint))
        if (battery.crankTestActive) {
            Button(onClick = viewModel::stopCrankTest, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.battery_crank_stop))
            }
            Text(stringResource(R.string.battery_crank_waiting), color = GaugeAmber)
        } else {
            Button(onClick = viewModel::startCrankTest, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.battery_crank_start))
            }
        }

        battery.cranking?.let { crank ->
            InfoRow(stringResource(R.string.battery_crank_min), "%.1f В".format(Locale.ROOT, crank.minVolts))
            InfoRow(stringResource(R.string.battery_crank_drop), "%.1f В".format(Locale.ROOT, crank.dropVolts))
            InfoRow(
                stringResource(R.string.battery_crank_duration),
                "%.1f с".format(Locale.ROOT, crank.durationMillis / 1000.0)
            )
            Text(crank.verdict.title, color = crankColor(crank.verdict))
        }
    }

    Panel {
        PanelTitle(stringResource(R.string.battery_current_title))
        Hint(stringResource(R.string.battery_current_text))
    }

    LeakCalculator(viewModel, battery.chargePercent)

    CalibrationPanel(viewModel)
}

/**
 * Сколько машина простоит с известной утечкой.
 *
 * Ток приложение не меряет и измерить не может, поэтому его приносят клещами
 * или мультиметром. Арифметику - на сколько суток хватит батареи - приложение
 * сделать может, и это ровно та часть, где от него есть польза.
 */
@Composable
private fun LeakCalculator(viewModel: ObdViewModel, chargePercent: Int?) {
    val profile by viewModel.profile.collectAsState()
    var text by remember { mutableStateOf("") }

    Panel {
        PanelTitle(stringResource(R.string.leak_title))
        Hint(stringResource(R.string.leak_hint, profile.batteryAh))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.leak_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        val milliAmps = text.replace(',', '.').trim().toDoubleOrNull()
        if (milliAmps != null && milliAmps > 0) {
            val verdict = Battery.leakVerdict(milliAmps)
            Text(verdict.title, color = leakColor(verdict))
            // Заряд берём измеренный, а если мотор работает - считаем от
            // полного: процент при работающем генераторе ничего не значит.
            val days = Battery.daysUntilFlat(profile.batteryAh, chargePercent ?: 100, milliAmps)
            if (days != null) {
                InfoRow(
                    stringResource(R.string.leak_days),
                    "%.1f".format(Locale.ROOT, days)
                )
                Hint(stringResource(R.string.leak_days_hint, chargePercent ?: 100))
            }
        }
    }
}

private fun leakColor(verdict: LeakVerdict): Color = when (verdict) {
    LeakVerdict.NORMAL -> GaugeGreen
    LeakVerdict.ELEVATED -> GaugeAmber
    LeakVerdict.HIGH -> GaugeRed
}

@Composable
private fun CalibrationPanel(viewModel: ObdViewModel) {
    var text by remember { mutableStateOf(formatOffset(viewModel.voltageCalibration)) }
    var error by remember { mutableStateOf(false) }

    Panel {
        PanelTitle(stringResource(R.string.battery_calibration_title))
        Hint(stringResource(R.string.battery_calibration_hint))
        OutlinedTextField(
            value = text,
            onValueChange = { entered ->
                text = entered
                // Запятая - потому что на русской раскладке именно её и наберут.
                val parsed = entered.replace(',', '.').trim().toDoubleOrNull()
                error = parsed == null && entered.isNotBlank()
                if (parsed != null) viewModel.updateVoltageOffset(parsed)
                if (entered.isBlank()) viewModel.updateVoltageOffset(0.0)
            },
            isError = error,
            label = { Text(stringResource(R.string.battery_calibration_label)) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatOffset(value: Double): String =
    if (value == 0.0) "" else "%.2f".format(Locale.ROOT, value)

/**
 * График напряжения.
 *
 * Шкала подбирается под сами данные, а не задаётся от нуля: весь интерес
 * лежит в диапазоне 9-15 В, и от нуля провал при пуске был бы незаметен.
 */
@Composable
private fun VoltageChart(history: List<Double>) {
    val lo = (history.min() - 0.2).coerceAtLeast(0.0)
    val hi = history.max() + 0.2
    val span = (hi - lo).coerceAtLeast(0.1)

    Column(Modifier.padding(top = 8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(90.dp)
        ) {
            val stepX = if (history.size > 1) size.width / (history.size - 1) else size.width
            val path = Path()
            history.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height - ((v - lo) / span * size.height).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = GaugeGreen, style = Stroke(width = 3f))
            // Линия 12.0 В: ниже неё батарея на стоянке уже разряжена.
            if (lo < 12.0 && hi > 12.0) {
                val y = size.height - ((12.0 - lo) / span * size.height).toFloat()
                drawLine(
                    color = TextMuted.copy(alpha = 0.4f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.5f
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("%.1f В".format(Locale.ROOT, lo), style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Text("%.1f В".format(Locale.ROOT, hi), style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

private fun verdictColor(verdict: BatteryVerdict): Color = when (verdict) {
    BatteryVerdict.RESTING_OK, BatteryVerdict.CHARGE_OK -> GaugeGreen
    BatteryVerdict.PARTIAL, BatteryVerdict.CHARGE_WEAK -> GaugeAmber
    BatteryVerdict.UNKNOWN -> TextMuted
    else -> GaugeRed
}

private fun crankColor(verdict: CrankVerdict): Color = when (verdict) {
    CrankVerdict.GOOD -> GaugeGreen
    CrankVerdict.FAIR -> GaugeAmber
    CrankVerdict.WEAK -> GaugeRed
}
