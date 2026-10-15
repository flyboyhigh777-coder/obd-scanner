package com.obdscanner.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.R
import androidx.compose.ui.text.style.TextOverflow
import com.obdscanner.app.ui.theme.cappedSp
import com.obdscanner.app.ui.theme.CirclePlum
import com.obdscanner.app.ui.theme.CircleSage
import com.obdscanner.app.ui.theme.CircleSlate
import com.obdscanner.app.ui.theme.CircleSteel
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeGreen
import com.obdscanner.app.ui.theme.GaugeRed
import com.obdscanner.app.ui.theme.GradientTopBar
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.MenuRow
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.RowGlyph
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted

/** Разделы диагностики. Порядок тот же, в каком они стоят в меню. */
private enum class DiagSection { CODES, QUICK_CHECK, MONITOR_TESTS, O2_SENSORS, COUNTERS }

/**
 * Диагностика: меню разделов, а не ещё четыре вкладки внизу.
 *
 * Почему меню, а не вкладки: разделов диагностики шесть, и внизу они не
 * помещаются - на телефоне 360 dp подписи начинают обрезаться уже на шестой.
 * Но главное даже не место. Меню показывает **состояние** каждого раздела до
 * того, как в него зашли: сколько кодов, завершены ли бортовые тесты, есть ли
 * тест вне пределов. Вкладка так не может - она одинаковая всегда, и чтобы
 * узнать, есть ли там что-то, надо открыть каждую.
 *
 * Так устроены все сканеры, и не от лени: диагностика - это не пять экранов,
 * которые смотрят по очереди, а пять вопросов, из которых обычно важен один.
 */
@Composable
fun DiagnosticsScreen(viewModel: ObdViewModel) {
    var section by rememberSaveable { mutableStateOf<DiagSection?>(null) }

    if (section == null) {
        DiagnosticsMenu(viewModel) { section = it }
        return
    }

    Column(Modifier.fillMaxWidth()) {
        BackBar(stringResource(sectionTitle(section!!))) { section = null }
        when (section!!) {
            DiagSection.CODES -> DtcScreen(viewModel)
            DiagSection.QUICK_CHECK -> QuickCheckPage(viewModel)
            DiagSection.MONITOR_TESTS -> MonitorTestsPage(viewModel)
            DiagSection.O2_SENSORS -> O2SensorsPage(viewModel)
            DiagSection.COUNTERS -> CountersPage(viewModel)
        }
    }
}

private fun sectionTitle(section: DiagSection): Int = when (section) {
    DiagSection.CODES -> R.string.diag_codes
    DiagSection.QUICK_CHECK -> R.string.diag_quick
    DiagSection.MONITOR_TESTS -> R.string.diag_monitor_tests
    DiagSection.O2_SENSORS -> R.string.diag_o2
    DiagSection.COUNTERS -> R.string.diag_counters
}

/**
 * Полоса "назад к меню".
 *
 * Своя, а не системная кнопка назад: экран живёт внутри вкладки, и системная
 * кнопка увела бы из приложения целиком. Нажимается вся полоса, а не только
 * стрелка - в машине попасть в стрелку 24 dp на ходу невозможно.
 */
@Composable
private fun BackBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Одна строка и ограниченный рост: это обрамление, а не данные, и при
        // крупном шрифте оно иначе занимает пол-экрана над самим разделом.
        Text("‹", style = MaterialTheme.typography.headlineSmall, color = TextMuted)
        Text(
            stringResource(R.string.diag_back),
            fontSize = cappedSp(14f, maxScale = 1.15f),
            color = TextMuted,
            maxLines = 1,
            softWrap = false
        )
        Text(
            "· $title",
            fontSize = cappedSp(14f, maxScale = 1.15f),
            color = TextBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DiagnosticsMenu(viewModel: ObdViewModel, onOpen: (DiagSection) -> Unit) {
    val connection by viewModel.connection.collectAsState()
    val connected = connection is ConnectionState.Connected
    val dtc by viewModel.dtc.collectAsState()
    val live by viewModel.live.collectAsState()
    val tests by viewModel.tests.collectAsState()
    val quick by viewModel.quickCheck.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GradientTopBar(stringResource(R.string.diag_title), stringResource(R.string.diag_subtitle))

        // Лампа Check Engine - первое, на что смотрят, поэтому она отдельной
        // строкой над меню, а не пунктом в нём.
        val monitor = live.monitor ?: dtc.monitor
        Panel {
            when {
                monitor == null -> Hint(stringResource(R.string.diag_mil_unknown))
                monitor.milOn -> Text(
                    stringResource(R.string.diag_mil_on, monitor.dtcCount),
                    style = MaterialTheme.typography.titleMedium,
                    color = GaugeRed
                )
                else -> Text(
                    stringResource(R.string.diag_mil_off),
                    style = MaterialTheme.typography.titleMedium,
                    color = GaugeGreen
                )
            }
            if (monitor != null) {
                val incomplete = monitor.incomplete.size
                Hint(
                    if (incomplete == 0) {
                        stringResource(R.string.diag_readiness_ok)
                    } else {
                        stringResource(R.string.diag_readiness_incomplete, incomplete)
                    }
                )
            }
        }

        Panel {
            val codeCount = dtc.stored.size + dtc.pending.size + dtc.permanent.size
            MenuRow(
                title = stringResource(R.string.diag_codes),
                description = stringResource(R.string.diag_codes_desc),
                glyph = if (codeCount > 0) RowGlyph.ALERT else RowGlyph.LIST,
                circleColor = if (codeCount > 0) GaugeRed else CircleSteel,
                state = when {
                    !dtc.loaded -> null
                    codeCount == 0 -> stringResource(R.string.diag_state_clean)
                    else -> stringResource(R.string.diag_state_codes, codeCount)
                },
                stateColor = if (codeCount > 0) GaugeRed else GaugeGreen,
                onClick = { onOpen(DiagSection.CODES) }
            )
            MenuRow(
                title = stringResource(R.string.diag_quick),
                description = stringResource(R.string.diag_quick_desc),
                glyph = RowGlyph.OK,
                circleColor = CircleSage,
                state = if (quick.report != null) stringResource(R.string.diag_state_ready) else null,
                onClick = { onOpen(DiagSection.QUICK_CHECK) }
            )
            MenuRow(
                title = stringResource(R.string.diag_monitor_tests),
                description = stringResource(R.string.diag_monitor_tests_desc),
                glyph = RowGlyph.GAUGE,
                circleColor = if (tests.failed.isNotEmpty()) GaugeAmber else CircleSteel,
                state = when {
                    !tests.loaded -> null
                    tests.monitorTests.isEmpty() -> stringResource(R.string.diag_state_none)
                    tests.failed.isEmpty() -> stringResource(R.string.diag_state_all_ok)
                    else -> stringResource(R.string.diag_state_failed, tests.failed.size)
                },
                stateColor = if (tests.failed.isEmpty()) GaugeGreen else GaugeAmber,
                onClick = { onOpen(DiagSection.MONITOR_TESTS) }
            )
            MenuRow(
                title = stringResource(R.string.diag_o2),
                description = stringResource(R.string.diag_o2_desc),
                glyph = RowGlyph.WAVE,
                circleColor = CirclePlum,
                state = tests.o2Locations.count { it.present }
                    .takeIf { tests.loaded && it > 0 }
                    ?.let { stringResource(R.string.diag_state_sensors, it) },
                onClick = { onOpen(DiagSection.O2_SENSORS) }
            )
            MenuRow(
                title = stringResource(R.string.diag_counters),
                description = stringResource(R.string.diag_counters_desc),
                glyph = RowGlyph.BARS,
                circleColor = if (tests.performance == null) CircleSlate else CircleSteel,
                state = tests.performance?.ignitionCycles?.let {
                    stringResource(R.string.diag_state_cycles, it)
                },
                onClick = { onOpen(DiagSection.COUNTERS) }
            )
        }

        if (!connected) {
            Panel { Hint(stringResource(R.string.tests_not_connected)) }
        }
    }
}
