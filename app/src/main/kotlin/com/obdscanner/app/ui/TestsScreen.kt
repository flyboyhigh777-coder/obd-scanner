package com.obdscanner.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.R
import com.obdscanner.app.data.ReportExport
import com.obdscanner.app.ui.theme.CirclePlum
import com.obdscanner.app.ui.theme.CircleSlate
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeGreen
import com.obdscanner.app.ui.theme.GaugeRed
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.InfoRow
import com.obdscanner.app.ui.theme.MeasureRow
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.PanelTitle
import com.obdscanner.app.ui.theme.RowGlyph
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted
import com.obdscanner.app.ui.theme.ValueRow
import com.obdscanner.core.diag.ReportBlock
import com.obdscanner.core.diag.ReportTone
import com.obdscanner.core.monitor.MonitorTest

/**
 * Разделы бортовых самодиагностик - то, что ЭБУ проверяет сам.
 *
 * Открываются из меню "Диагностика", а не вкладкой: вкладка одинаковая всегда,
 * а в меню у каждого раздела видно состояние ещё до того, как в него зашли.
 *
 * Разница с разделом "Ошибки" принципиальная: там записанные неисправности, то
 * есть прошлое, а здесь результаты самодиагностик с их пределами - то есть
 * насколько близко узел подошёл к порогу, за которым ошибка появится.
 */
@Composable
private fun TestPage(viewModel: ObdViewModel, content: @Composable ColumnScope.(Boolean) -> Unit) {
    val connection by viewModel.connection.collectAsState()
    val connected = connection is ConnectionState.Connected
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!connected) {
            Panel { Hint(stringResource(R.string.tests_not_connected)) }
        }
        content(connected)
    }
}

/** Полная проверка и отчёт. */
@Composable
fun QuickCheckPage(viewModel: ObdViewModel) = TestPage(viewModel) { connected ->
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
            Text(stringResource(R.string.tests_quick_step, state.step?.title.orEmpty()), color = GaugeAmber)
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
    }

    val report = state.report
    if (report != null) {
        Panel {
            PanelTitle(stringResource(R.string.tests_quick_verdict))
            Text(
                report.verdict,
                style = MaterialTheme.typography.titleSmall,
                color = if (report.dtcTotal > 0 || report.failedTests.isNotEmpty()) GaugeRed else GaugeGreen
            )
            ValueRow(stringResource(R.string.tests_quick_codes), report.dtcTotal.toString())
            ValueRow(
                stringResource(R.string.tests_quick_failed),
                stringResource(R.string.tests_failed_count, report.failedTests.size, report.monitorTests.size)
            )
            report.skipped.forEach { Hint(it) }

            // Сохранение: PDF первым и шире - он открывается на телефоне и
            // печатается, а текст нужен тем, кто вставляет отчёт в сообщение.
            val save = { asPdf: Boolean ->
                viewModel.exportQuickCheck(asPdf) { result ->
                    message = context.getString(
                        when {
                            !result.success -> R.string.tests_quick_save_failed
                            result.inDownloads -> R.string.tests_quick_saved_downloads
                            else -> R.string.tests_quick_saved_cache
                        }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(true) }, modifier = Modifier.weight(1.4f)) {
                    Text(stringResource(R.string.tests_quick_save_pdf))
                }
                OutlinedButton(onClick = { save(false) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tests_quick_save_text), color = TextBright)
                }
            }

            val uri = state.exportedUri
            OutlinedButton(
                onClick = {
                    if (uri != null) {
                        context.startActivity(
                            Intent.createChooser(
                                ReportExport.shareIntent(uri, state.exportedMime ?: ReportExport.MIME_PDF),
                                context.getString(R.string.tests_quick_share)
                            )
                        )
                    }
                },
                enabled = uri != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.tests_quick_share), color = TextBright) }
            message?.let { Hint(it) }
        }

        Panel {
            PanelTitle(stringResource(R.string.tests_quick_report))
            // Отчёт строками интерфейса, а не одним куском текста: кусок текста
            // приходится набирать мелким, чтобы в него влезали строки целиком,
            // и на телефоне он нечитаем - а ошибки смотрят именно здесь.
            report.blocks().forEach { block -> ReportBlockRow(block) }
        }
    }
}

/** Одна строка отчёта на экране. Цвет берётся из самого отчёта, а не угадывается. */
@Composable
private fun ReportBlockRow(block: ReportBlock) {
    val color = { tone: ReportTone ->
        when (tone) {
            ReportTone.PLAIN -> TextBright
            ReportTone.GOOD -> GaugeGreen
            ReportTone.WARN -> GaugeAmber
            ReportTone.BAD -> GaugeRed
        }
    }
    when (block) {
        is ReportBlock.Title -> {
            Text(block.text, style = MaterialTheme.typography.titleMedium, color = TextBright)
            if (block.subtitle.isNotEmpty()) Hint(block.subtitle)
        }

        is ReportBlock.Section -> Text(
            block.text,
            style = MaterialTheme.typography.titleSmall,
            color = TextMuted,
            modifier = Modifier.padding(top = 10.dp)
        )

        is ReportBlock.Row -> InfoRow(block.label, block.value, color(block.tone))

        is ReportBlock.Note -> Text(
            block.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (block.tone == ReportTone.PLAIN) TextMuted else color(block.tone),
            modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
        )

        ReportBlock.Space -> Unit
    }
}

/** Режим 06: результаты с пределами. */
@Composable
fun MonitorTestsPage(viewModel: ObdViewModel) = TestPage(viewModel) { connected ->
    val state by viewModel.tests.collectAsState()

    Panel {
        PanelTitle(stringResource(R.string.tests_monitor_title))
        Hint(stringResource(R.string.tests_monitor_hint))
        ReadButton(viewModel, state.running, connected)
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
}

/** Лямбда-зонды: расположение и тесты режима 05 там, где он есть. */
@Composable
fun O2SensorsPage(viewModel: ObdViewModel) = TestPage(viewModel) { connected ->
    val state by viewModel.tests.collectAsState()

    Panel {
        PanelTitle(stringResource(R.string.tests_o2_title))
        Hint(stringResource(R.string.tests_o2_hint))
        ReadButton(viewModel, state.running, connected)
    }

    if (state.o2Locations.any { it.present }) {
        Panel {
            PanelTitle(stringResource(R.string.tests_o2_present_title))
            state.o2Locations.filter { it.present }.forEach { location ->
                InfoRow(location.title, stringResource(R.string.tests_o2_present), GaugeGreen)
            }
        }
    }

    Panel {
        PanelTitle(stringResource(R.string.tests_o2_mode05_title))
        if (state.o2Tests.isEmpty()) {
            Hint(stringResource(R.string.tests_o2_mode05_can))
        } else {
            state.o2Tests.forEach { test ->
                ValueRow("${test.sensorTitle}: ${test.title}", test.format())
            }
        }
    }
}

/** Счётчики наработки бортовых тестов. */
@Composable
fun CountersPage(viewModel: ObdViewModel) = TestPage(viewModel) { connected ->
    val state by viewModel.tests.collectAsState()
    val performance = state.performance

    Panel {
        PanelTitle(stringResource(R.string.tests_ipt_title))
        Hint(stringResource(R.string.tests_ipt_hint))
        if (performance == null) {
            ReadButton(viewModel, state.running, connected)
            if (state.loaded) Hint(stringResource(R.string.tests_ipt_empty))
        }
    }

    if (performance != null) {
        Panel {
            PanelTitle(stringResource(R.string.tests_ipt_general))
            performance.obdConditions?.let {
                ValueRow(stringResource(R.string.tests_ipt_conditions), it.toString())
            }
            performance.ignitionCycles?.let {
                ValueRow(stringResource(R.string.tests_ipt_ignitions), it.toString())
            }
            Hint(stringResource(R.string.tests_ipt_ratio_hint))
        }
        Panel {
            PanelTitle(stringResource(R.string.tests_ipt_pairs))
            performance.pairs.forEach { pair ->
                val ratio = pair.ratio?.times(100.0)
                InfoRow(
                    pair.title,
                    if (ratio == null) {
                        stringResource(R.string.tests_ipt_no_conditions)
                    } else {
                        stringResource(
                            R.string.tests_ipt_ratio,
                            pair.completed.value,
                            pair.conditions.value,
                            ratio.toInt()
                        )
                    },
                    when {
                        ratio == null -> TextMuted
                        // 0,336 - то самое отношение, ниже которого бортовая
                        // диагностика считается неработающей по европейским
                        // правилам. Своих чисел здесь нет и быть не может.
                        ratio < 33.6 -> GaugeAmber
                        else -> GaugeGreen
                    }
                )
            }
        }
    }
}

/** Кнопка "прочитать" с остановкой - одна и та же во всех трёх разделах. */
@Composable
private fun ReadButton(viewModel: ObdViewModel, running: Boolean, connected: Boolean) {
    if (running) {
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
}

/**
 * Одна строка результата: название теста, значение между пределами и вердикт.
 *
 * Раскладка в четыре колонки (минимум, значение, максимум, единица) - потому
 * что смысл режима 06 не в значении, а в его расстоянии до предела: пока три
 * числа стоят в одной строке, это видно без арифметики в голове.
 */
@Composable
private fun MonitorTestRow(test: MonitorTest) {
    val color = when (test.passed) {
        true -> GaugeGreen
        false -> GaugeRed
        null -> TextMuted
    }
    MeasureRow(
        title = test.testTitle,
        description = when (test.passed) {
            true -> stringResource(R.string.tests_pass)
            false -> stringResource(R.string.tests_fail)
            null -> stringResource(R.string.tests_no_limits)
        },
        glyph = when (test.passed) {
            true -> RowGlyph.OK
            false -> RowGlyph.ALERT
            null -> RowGlyph.UNKNOWN
        },
        circleColor = when (test.passed) {
            true -> GaugeGreen
            false -> GaugeRed
            null -> CircleSlate
        },
        min = test.minText(),
        value = test.valueText(),
        max = test.maxText(),
        unit = test.unit,
        valueColor = color,
        position = test.position,
        labels = Triple(
            stringResource(R.string.tests_limit_min_short),
            stringResource(R.string.tests_value_short),
            stringResource(R.string.tests_limit_max_short)
        )
    )
}
