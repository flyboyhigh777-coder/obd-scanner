package com.obdscanner.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.R
import com.obdscanner.app.data.ReportExport
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeGreen
import com.obdscanner.app.ui.theme.GaugeRed
import com.obdscanner.app.ui.theme.GradientTopBar
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.InfoRow
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.PanelBorder
import com.obdscanner.app.ui.theme.PanelTitle
import com.obdscanner.app.ui.theme.SegmentButton
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted
import com.obdscanner.core.monitor.MonitorTest

/**
 * Вкладка "Тесты" - то, что ЭБУ проверяет сам.
 *
 * Разница с вкладкой "Ошибки" принципиальная и стоит того, чтобы её развести по
 * экранам: там записанные неисправности, то есть прошлое, а здесь результаты
 * самодиагностик с их пределами - то есть насколько близко узел подошёл к
 * порогу, за которым ошибка появится. Катализатор, который "пока проходит", и
 * лямбда-зонд, который начал залениваться, видно только здесь.
 *
 * Сюда же вынесена полная проверка одним нажатием с отчётом: её запускают, когда
 * машину смотрят целиком - перед покупкой, перед техосмотром, после ремонта.
 */
@Composable
fun TestsScreen(viewModel: ObdViewModel) {
    var section by rememberSaveable { mutableStateOf(0) }
    val connection by viewModel.connection.collectAsState()
    val connected = connection is ConnectionState.Connected

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GradientTopBar(stringResource(R.string.tests_title), stringResource(R.string.tests_subtitle))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentButton(stringResource(R.string.tests_tab_quick), section == 0, Modifier.weight(1f)) {
                section = 0
            }
            SegmentButton(stringResource(R.string.tests_tab_monitors), section == 1, Modifier.weight(1f)) {
                section = 1
            }
            SegmentButton(stringResource(R.string.tests_tab_counters), section == 2, Modifier.weight(1f)) {
                section = 2
            }
        }

        if (!connected) {
            Panel { Hint(stringResource(R.string.tests_not_connected)) }
        }

        when (section) {
            0 -> QuickCheckSection(viewModel, connected)
            1 -> MonitorTestsSection(viewModel, connected)
            else -> CountersSection(viewModel, connected)
        }
    }
}

/** Полная проверка и отчёт. */
@Composable
private fun QuickCheckSection(viewModel: ObdViewModel, connected: Boolean) {
    val state by viewModel.quickCheck.collectAsState()
    val withMonitorTests by viewModel.quickCheckMonitorTests.collectAsState()
    val context = LocalContext.current
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    Panel {
        PanelTitle(stringResource(R.string.tests_quick_title))
        Hint(stringResource(R.string.tests_quick_hint))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tests_quick_mode06), color = TextBright, modifier = Modifier.weight(1f))
            Switch(
                checked = withMonitorTests,
                onCheckedChange = { viewModel.setQuickCheckMonitorTests(it) },
                enabled = !state.running
            )
        }
        Hint(stringResource(R.string.tests_quick_mode06_hint))

        if (state.running) {
            Text(
                stringResource(R.string.tests_quick_step, state.step?.title.orEmpty()),
                color = GaugeAmber
            )
            OutlinedButton(onClick = { viewModel.stopQuickCheck() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tests_stop), color = TextBright)
            }
        } else {
            Button(
                onClick = { viewModel.runQuickCheck() },
                enabled = connected,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.tests_quick_run)) }
        }

        val report = state.report
        if (report != null) {
            InfoRow(
                stringResource(R.string.tests_quick_verdict),
                report.verdict,
                if (report.dtcTotal > 0 || report.failedTests.isNotEmpty()) GaugeRed else GaugeGreen
            )
            InfoRow(stringResource(R.string.tests_quick_codes), report.dtcTotal.toString())
            InfoRow(
                stringResource(R.string.tests_quick_failed),
                stringResource(
                    R.string.tests_failed_count,
                    report.failedTests.size,
                    report.monitorTests.size
                )
            )
            report.skipped.forEach { Hint(it) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.exportQuickCheck { result ->
                            message = context.getString(
                                when {
                                    !result.success -> R.string.tests_quick_save_failed
                                    result.inDownloads -> R.string.tests_quick_saved_downloads
                                    else -> R.string.tests_quick_saved_cache
                                }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.tests_quick_save)) }

                val uri = state.exportedUri
                OutlinedButton(
                    onClick = {
                        if (uri != null) {
                            context.startActivity(
                                Intent.createChooser(
                                    ReportExport.shareIntent(uri),
                                    context.getString(R.string.tests_quick_share)
                                )
                            )
                        }
                    },
                    enabled = uri != null,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.tests_quick_share), color = TextBright) }
            }
            message?.let { Hint(it) }

            // Сам отчёт целиком - его и читают, и копируют в переписку.
            Text(
                report.toText(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = TextMuted
            )
        }
    }
}

/** Режим 06: результаты с пределами, и режим 05 там, где он есть. */
@Composable
private fun MonitorTestsSection(viewModel: ObdViewModel, connected: Boolean) {
    val state by viewModel.tests.collectAsState()

    Panel {
        PanelTitle(stringResource(R.string.tests_monitor_title))
        Hint(stringResource(R.string.tests_monitor_hint))

        if (state.running) {
            OutlinedButton(onClick = { viewModel.stopTests() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tests_stop), color = TextBright)
            }
            Text(stringResource(R.string.tests_reading), color = GaugeAmber)
        } else {
            Button(
                onClick = { viewModel.readTests() },
                enabled = connected,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.tests_read)) }
        }

        state.message?.let { Hint(it) }

        if (state.monitorTests.isNotEmpty()) {
            InfoRow(
                stringResource(R.string.tests_quick_failed),
                stringResource(R.string.tests_failed_count, state.failed.size, state.monitorTests.size),
                if (state.failed.isEmpty()) GaugeGreen else GaugeRed
            )
        }
    }

    // Группировка по монитору: у одного монитора несколько тестов, и читать их
    // вперемешку невозможно.
    state.monitorTests.groupBy { it.mid }.forEach { (_, tests) ->
        Panel {
            PanelTitle(tests.first().title)
            tests.forEach { MonitorTestRow(it) }
        }
    }

    if (state.o2Locations.any { it.present } || state.o2Tests.isNotEmpty()) {
        Panel {
            PanelTitle(stringResource(R.string.tests_o2_title))
            Hint(stringResource(R.string.tests_o2_hint))
            state.o2Locations.filter { it.present }.forEach { location ->
                InfoRow(location.title, stringResource(R.string.tests_o2_present))
            }
            if (state.o2Tests.isEmpty()) {
                Hint(stringResource(R.string.tests_o2_mode05_can))
            } else {
                state.o2Tests.forEach { test ->
                    InfoRow("${test.sensorTitle}: ${test.title}", test.format())
                }
            }
        }
    }
}

/**
 * Одна строка результата: значение, пределы и полоска, показывающая, где
 * значение стоит между ними.
 *
 * Полоска здесь не украшение - она и есть ответ на вопрос "сколько осталось".
 * Число 42 при пределе 36 ничего не говорит человеку, который не знает, что
 * это за величина; полоска, упёршаяся в край, говорит.
 */
@Composable
private fun MonitorTestRow(test: MonitorTest) {
    val color = when (test.passed) {
        true -> GaugeGreen
        false -> GaugeRed
        null -> TextMuted
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(test.testTitle, color = TextBright, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(test.formatValue(), color = color, fontWeight = FontWeight.Medium)
            Text(
                when (test.passed) {
                    true -> stringResource(R.string.tests_pass)
                    false -> stringResource(R.string.tests_fail)
                    null -> stringResource(R.string.tests_no_limits)
                },
                color = color,
                style = MaterialTheme.typography.bodySmall
            )
        }
        val limits = listOfNotNull(
            test.formatMin()?.let { stringResource(R.string.tests_limit_min, it) },
            test.formatMax()?.let { stringResource(R.string.tests_limit_max, it) }
        ).joinToString(" · ")
        if (limits.isNotEmpty()) Hint(limits)
        test.position?.let { position ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(PanelBorder)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(position.coerceIn(0.0, 1.0).toFloat())
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (test.passed == false) GaugeRed else color.copy(alpha = 0.8f))
                )
            }
        }
    }
}

/** Счётчики наработки бортовых тестов: завершено и сколько раз были условия. */
@Composable
private fun CountersSection(viewModel: ObdViewModel, connected: Boolean) {
    val state by viewModel.tests.collectAsState()
    val performance = state.performance

    Panel {
        PanelTitle(stringResource(R.string.tests_ipt_title))
        Hint(stringResource(R.string.tests_ipt_hint))
        if (performance == null) {
            Button(
                onClick = { viewModel.readTests() },
                enabled = connected && !state.running,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.tests_read)) }
            if (state.loaded) Hint(stringResource(R.string.tests_ipt_empty))
        }
    }

    if (performance != null) {
        Panel {
            PanelTitle(stringResource(R.string.tests_ipt_general))
            performance.obdConditions?.let {
                InfoRow(stringResource(R.string.tests_ipt_conditions), it.toString())
            }
            performance.ignitionCycles?.let {
                InfoRow(stringResource(R.string.tests_ipt_ignitions), it.toString())
            }
            Hint(stringResource(R.string.tests_ipt_ratio_hint))
        }
        Panel {
            PanelTitle(stringResource(R.string.tests_ipt_pairs))
            performance.pairs.forEach { pair ->
                val completed = pair.completed
                val conditions = pair.conditions
                val ratio = pair.ratio?.times(100.0)
                InfoRow(
                    pair.title,
                    if (ratio == null) {
                        stringResource(R.string.tests_ipt_no_conditions)
                    } else {
                        stringResource(
                            R.string.tests_ipt_ratio,
                            completed.value,
                            conditions.value,
                            ratio.toInt()
                        )
                    },
                    when {
                        ratio == null -> TextMuted
                        ratio < 33.6 -> GaugeAmber
                        else -> GaugeGreen
                    }
                )
            }
        }
    }
}
