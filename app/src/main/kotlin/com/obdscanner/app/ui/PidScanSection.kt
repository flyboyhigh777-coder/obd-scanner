package com.obdscanner.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.R
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeGreen
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.PanelTitle
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted
import com.obdscanner.core.obd.PidScan

/**
 * Поиск заводских параметров перебором.
 *
 * Нужен там, где документации нет. Списка номеров режима 22 для ME17.9.7x в
 * открытом виде не существует, зато блок управления отвечает честно: на
 * несуществующий номер - отказ, на существующий - значение. Поэтому номер
 * ищется опросом конкретной машины, а не чтением форумов.
 *
 * Главное здесь - не сам перебор, а сравнение "до и после": снять снимок,
 * включить фары или обогрев стекла, сравнить. Параметр, который изменился
 * вместе с нагрузкой, и есть искомый.
 */
@Composable
fun PidScanSection(viewModel: ObdViewModel, onUseHit: (Int) -> Unit) {
    val scan by viewModel.pidScan.collectAsState()
    var from by rememberSaveable { mutableStateOf("F400") }
    var to by rememberSaveable { mutableStateOf("F4FF") }

    Panel {
        PanelTitle(stringResource(R.string.scan_title))
        Hint(stringResource(R.string.scan_hint))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = from,
                onValueChange = { from = it.uppercase().take(4) },
                label = { Text(stringResource(R.string.scan_from)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = to,
                onValueChange = { to = it.uppercase().take(4) },
                label = { Text(stringResource(R.string.scan_to)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        // Готовые куски диапазона: перебирать все 65 536 номеров - это часы.
        PidScan.PRESETS.forEach { preset ->
            TextButton(onClick = {
                from = "%04X".format(preset.from)
                to = "%04X".format(preset.to)
            }) {
                Text(preset.title, style = MaterialTheme.typography.bodySmall)
            }
        }

        val rangeFrom = from.toIntOrNull(16)
        val rangeTo = to.toIntOrNull(16)
        val valid = rangeFrom != null && rangeTo != null

        if (scan.running) {
            val progress = if (scan.total > 0) scan.done.toFloat() / scan.total else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.scan_progress, scan.done, scan.total, scan.hits.size),
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = viewModel::stopPidScan, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.scan_stop))
            }
        } else {
            Button(
                onClick = { if (valid) viewModel.startPidScan(rangeFrom!!, rangeTo!!) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.scan_start)) }
            if (!valid) Text(stringResource(R.string.scan_bad_range), color = GaugeAmber)
        }

        scan.message?.let { Text(it, color = GaugeAmber) }
    }

    if (scan.hits.isNotEmpty()) {
        Panel {
            PanelTitle(stringResource(R.string.scan_compare_title))
            Hint(stringResource(R.string.scan_compare_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::takePidSnapshot,
                    enabled = !scan.running,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.scan_snapshot)) }
                Button(
                    onClick = viewModel::comparePidSnapshot,
                    enabled = !scan.running && scan.snapshotSize > 0,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.scan_compare)) }
            }
            if (scan.snapshotSize > 0) {
                Hint(stringResource(R.string.scan_snapshot_taken, scan.snapshotSize))
            }

            scan.changes.forEach { change ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "22${change.label}",
                        color = GaugeGreen,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(
                            R.string.scan_change_line,
                            change.before.hex,
                            change.after.hex,
                            change.delta
                        ),
                        color = TextBright,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Panel {
            PanelTitle(stringResource(R.string.scan_found_title, scan.hits.size))
            Hint(stringResource(R.string.scan_found_hint))
            scan.hits.forEach { hit ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(hit.request, color = TextBright, style = MaterialTheme.typography.bodyMedium)
                    Text(hit.hex, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onUseHit(hit.pid) }) {
                        Text(stringResource(R.string.scan_use), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TextButton(onClick = viewModel::clearPidScan) {
                Text(stringResource(R.string.scan_clear), color = GaugeAmber)
            }
        }
    }
}
