package com.obdscanner.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.R
import com.obdscanner.core.pro.ProFeature
import com.obdscanner.app.data.DocumentationDtc
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.GaugeGreen
import com.obdscanner.app.ui.theme.GaugeRed
import com.obdscanner.app.ui.theme.GradientTopBar
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.InfoRow
import com.obdscanner.app.ui.theme.Panel
import com.obdscanner.app.ui.theme.PanelTitle
import com.obdscanner.app.ui.theme.StatusDot
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.app.ui.theme.TextMuted
import com.obdscanner.core.dtc.Dtc
import com.obdscanner.core.dtc.DtcArchive
import com.obdscanner.core.dtc.DtcSeverity
import com.obdscanner.core.dtc.DtcSnapshot
import com.obdscanner.core.dtc.DtcTextSource
import com.obdscanner.core.modules.ModuleDtc
import com.obdscanner.core.modules.ModuleReply
import com.obdscanner.core.modules.ModuleScan
import com.obdscanner.core.obd.Pids
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ошибки: чтение сохранённых, ожидающих и постоянных кодов, стоп-кадр,
 * готовность бортовых тестов и сброс - с честным предупреждением о том, что
 * сброс делает и чего не делает.
 */
@Composable
fun DtcScreen(viewModel: ObdViewModel) {
    val connection by viewModel.connection.collectAsState()
    val dtc by viewModel.dtc.collectAsState()
    val units by viewModel.units.collectAsState()
    val tableRevision by viewModel.dtcTableRevision.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    // Код, для которого открыт диалог своей расшифровки.
    var editing by remember { mutableStateOf<String?>(null) }

    val pickTable = rememberLauncherForActivityResult(
        // Любой тип: таблицу выгружают и как .txt, и как .csv, и как .tsv, а
        // некоторые файловые менеджеры отдают её вообще без типа.
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importDtcTable(uri) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GradientTopBar(stringResource(R.string.dtc_title))

        if (connection !is ConnectionState.Connected) {
            // Без связи экран раньше был пуст - а именно в этот момент коды и
            // начинают разглядывать: адаптер вынут, человек сидит дома.
            Panel { Hint(stringResource(R.string.dash_offline)) }
            ArchivePanel(viewModel, offline = true)
            return@Column
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = viewModel::readDtcs, enabled = !dtc.reading) {
                    Text(stringResource(if (dtc.reading) R.string.dtc_reading else R.string.dtc_read))
                }
                if (dtc.reading) CircularProgressIndicator()
            }
            dtc.message?.let { Text(it, color = GaugeAmber) }
            OutlinedButton(
                onClick = { pickTable.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.dtc_import_table)) }
            Hint(stringResource(R.string.dtc_import_hint))
            // Счётчик живёт в хранилище, а не в состоянии экрана: пересчитываем
            // его вместе с ревизией, иначе Compose о правке не узнает.
            val ownCount = remember(tableRevision) { DocumentationDtc.userSize }
            if (ownCount > 0) {
                Hint(stringResource(R.string.dtc_own_count, ownCount))
                TextButton(onClick = viewModel::clearDtcDescriptions) {
                    Text(stringResource(R.string.dtc_own_clear), color = GaugeAmber)
                }
            }
            if (dtc.loaded && dtc.failed) {
                // Блок промолчал. Зелёная точка здесь была бы враньём.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(GaugeAmber)
                    Text(stringResource(R.string.dtc_read_failed), color = TextBright)
                }
            } else if (dtc.loaded && dtc.total == 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(GaugeGreen)
                    Text(stringResource(R.string.dtc_none), color = TextBright)
                }
            }
            // Счётчик кодов в ответе о лампе и список из режима 03 - разные
            // источники, и они расходятся. Молча показать "ошибок нет" под
            // счётчиком "2" нельзя: человек решит, что приложение врёт.
            val counted = dtc.monitor?.dtcCount ?: 0
            if (dtc.loaded && !dtc.failed && counted > dtc.stored.size) {
                Hint(stringResource(R.string.dtc_count_mismatch, counted, dtc.stored.size))
            }
        }

        if (dtc.stored.isNotEmpty()) {
            DtcGroup(stringResource(R.string.dtc_group_stored), dtc.stored, null, tableRevision) { editing = it }
        }
        if (dtc.pending.isNotEmpty()) {
            DtcGroup(
                stringResource(R.string.dtc_group_pending),
                dtc.pending,
                stringResource(R.string.dtc_pending_hint),
                tableRevision
            ) { editing = it }
        }
        if (dtc.permanent.isNotEmpty()) {
            DtcGroup(
                stringResource(R.string.dtc_group_permanent),
                dtc.permanent,
                stringResource(R.string.dtc_permanent_hint),
                tableRevision
            ) { editing = it }
        }

        if (dtc.freezeFrame.isNotEmpty()) {
            Panel {
                PanelTitle(stringResource(R.string.dtc_freeze_title))
                Hint(stringResource(R.string.dtc_freeze_hint))
                dtc.freezeFrame.forEach { (id, value) ->
                    val pid = Pids.byId[id] ?: return@forEach
                    InfoRow(pid.title, Format.value(pid, value, units))
                }
            }
        }

        ModulesPanel(viewModel)

        dtc.monitor?.let { monitor ->
            Panel {
                PanelTitle(stringResource(R.string.dtc_monitors_title))
                if (monitor.readyForInspection) {
                    Text(stringResource(R.string.dtc_monitors_ready), color = GaugeGreen)
                } else {
                    Text(
                        stringResource(R.string.dtc_monitors_not_ready, monitor.incomplete.size),
                        color = GaugeAmber
                    )
                }
                val completeText = stringResource(R.string.dtc_monitor_complete)
                val incompleteText = stringResource(R.string.dtc_monitor_incomplete)
                monitor.monitors.filter { it.supported }.forEach { test ->
                    InfoRow(
                        label = if (ru()) test.ru else test.en,
                        value = if (test.complete) completeText else incompleteText,
                        valueColor = if (test.complete) GaugeGreen else GaugeAmber
                    )
                }
            }
        }

        ArchivePanel(viewModel, offline = false)

        if (dtc.stored.isNotEmpty() || dtc.pending.isNotEmpty()) {
            Button(
                onClick = { confirmClear = true },
                enabled = !dtc.reading,
                colors = ButtonDefaults.buttonColors(containerColor = GaugeRed, contentColor = TextBright),
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.dtc_clear)) }
        }
    }

    editing?.let { code ->
        OwnDescriptionDialog(
            code = code,
            initial = DocumentationDtc.userText(code).orEmpty(),
            onDismiss = { editing = null },
            onSave = { text ->
                viewModel.setDtcDescription(code, text)
                editing = null
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.dtc_clear_title)) },
            text = { Text(stringResource(R.string.dtc_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearDtcs()
                }) { Text(stringResource(R.string.dtc_clear_confirm), color = GaugeRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.dtc_cancel)) }
            }
        )
    }
}

@Composable
private fun DtcGroup(
    title: String,
    codes: List<Dtc>,
    hint: String?,
    tableRevision: Int,
    onEdit: (String) -> Unit,
) {
    Panel {
        PanelTitle("$title · ${codes.size}")
        if (hint != null) Hint(hint)
        codes.forEach { code -> DtcRow(code, tableRevision, onEdit) }
    }
}

@Composable
private fun DtcRow(dtc: Dtc, tableRevision: Int, onEdit: (String) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(severityColor(dtc.severity))
            Text(dtc.code, style = MaterialTheme.typography.titleMedium, color = TextBright, fontWeight = FontWeight.Bold)
            Text(dtc.severity.title, style = MaterialTheme.typography.bodySmall, color = severityColor(dtc.severity))
        }
        // Расшифровка лежит в справочнике :core, и Compose не может следить за
        // ней сам: пересчитываем её, когда человек правит свою таблицу.
        val details = remember(dtc.code, tableRevision) { dtc.descriptionDetails }
        Text(details.text, style = MaterialTheme.typography.bodyMedium, color = TextBright)
        Text(dtc.group, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        // Откуда взята расшифровка - важно: таблица блока управления и
        // справочник обобщённых кодов это не одно и то же, а описание семейства
        // вообще не диагноз, и выдавать их за одно было бы обманом.
        details.sourceNote?.let { Hint(stringResource(R.string.dtc_source_prefix, it)) }
        if (details.source == DtcTextSource.FAMILY) {
            Hint(stringResource(R.string.dtc_manufacturer_note))
        }
        // Причины идут по убыванию вероятности - так их и показываем, списком,
        // а не одной строкой: с этого начинают проверку.
        if (details.causes.isNotEmpty()) {
            PhraseList(stringResource(R.string.dtc_causes), details.causes)
        }
        if (details.symptoms.isNotEmpty()) {
            PhraseList(stringResource(R.string.dtc_symptoms), details.symptoms)
        }
        TextButton(onClick = { onEdit(dtc.code) }) {
            Text(stringResource(R.string.dtc_own_action), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PhraseList(title: String, phrases: List<String>) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        phrases.forEach { phrase ->
            Text(
                "• $phrase",
                style = MaterialTheme.typography.bodySmall,
                color = TextBright,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/**
 * Своя расшифровка одного кода.
 *
 * Это и есть ответ на то, что машиночитаемой таблицы кодов P1xxx для ME17.9.7x
 * в открытом доступе нет: расшифровку, найденную в руководстве по ремонту,
 * человек вписывает один раз сам, и приложение перестаёт зависеть от того,
 * удалось ли автору её где-то достать.
 */
/**
 * Блоки, которых нет в OBD-II: ABS, подушки, АКПП, электроусилитель.
 *
 * Самый частый вопрос к любому дешёвому сканеру: "лампа ABS горит, а ошибок
 * нет - почему?". Потому что стандартные режимы 03/07/0A спрашивают только
 * блок управления двигателем. Остальные блоки сидят на той же шине, но со
 * своими адресами, и адреса эти производитель не публикует - поэтому их
 * приходится искать перебором.
 */
@Composable
private fun ModulesPanel(viewModel: ObdViewModel) {
    val modules by viewModel.modules.collectAsState()
    val access by viewModel.access.collectAsState()
    var proFor by remember { mutableStateOf<ProFeature?>(null) }

    proFor?.let { feature -> ProDialog(feature) { proFor = null } }

    Panel {
        PanelTitle(stringResource(R.string.modules_title))
        Hint(stringResource(R.string.modules_hint))

        if (modules.loaded && !modules.possible) {
            Text(stringResource(R.string.modules_not_possible), color = GaugeAmber)
            return@Panel
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (modules.running) {
                Button(onClick = viewModel::stopModuleScan) { Text(stringResource(R.string.modules_stop)) }
                CircularProgressIndicator()
            } else if (access.allows(ProFeature.MODULE_SCAN)) {
                Button(onClick = viewModel::scanModules) { Text(stringResource(R.string.modules_scan)) }
            } else {
                // Кнопка остаётся на месте и ведёт в объяснение: пропавший
                // раздел читается как «приложение не умеет», и человек идёт
                // ставить другое вместо того, чтобы заплатить.
                ProButton(stringResource(R.string.modules_scan)) { proFor = ProFeature.MODULE_SCAN }
            }
        }

        if (modules.running && modules.total > 0) {
            // Перебор идёт полминуты, и без доли сделанного это выглядит как
            // зависшее приложение.
            LinearProgressIndicator(
                progress = { modules.done.toFloat() / modules.total },
                modifier = Modifier.fillMaxWidth()
            )
            Hint(stringResource(R.string.modules_progress, modules.done, modules.total))
        }

        if (modules.loaded && !modules.running && modules.found.isEmpty()) {
            Text(stringResource(R.string.modules_none), color = TextBright)
        }
    }

    modules.found.forEach { module ->
        Panel {
            PanelTitle(stringResource(R.string.modules_module, module.hex))
            if (ModuleScan.isStandardObd(module.address)) {
                Hint(stringResource(R.string.modules_engine_note))
            }
            module.systems.forEach { Hint(it) }

            when (val reply = module.reply) {
                is ModuleReply.Refused -> Text(
                    stringResource(R.string.modules_refused, reply.reason),
                    color = GaugeAmber
                )

                is ModuleReply.Codes -> {
                    Hint(reply.protocol.title)
                    if (reply.dtcs.isEmpty()) {
                        Text(stringResource(R.string.modules_no_codes), color = GaugeGreen)
                    }
                    reply.dtcs.forEach { dtc ->
                        ModuleDtcRow(dtc)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleDtcRow(dtc: ModuleDtc) {
    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (dtc.active == true) GaugeRed else GaugeAmber)
            Text(dtc.full, color = TextBright, style = MaterialTheme.typography.titleMedium)
        }
        val described = dtc.described
        Text(described.description, color = TextMuted)
        // Статус из UDS говорит больше, чем сам код: одно дело - неисправность
        // прямо сейчас, другое - запись о том, что было когда-то.
        val status = buildList {
            if (dtc.active == true) add(stringResource(R.string.modules_status_active))
            if (dtc.confirmed == true) add(stringResource(R.string.modules_status_confirmed))
            if (dtc.lampRequested == true) add(stringResource(R.string.modules_status_lamp))
        }
        if (status.isNotEmpty()) Hint(status.joinToString(" | "))
        dtc.failureType?.let { Hint(stringResource(R.string.modules_failure_type, it)) }
    }
}

/**
 * Архив прочитанного.
 *
 * Самое нужное здесь - то, что он виден БЕЗ машины. Коды читают в гараже, а
 * разбираются с ними потом, дома и с поиском; пока список жил только в памяти
 * приложения, он пропадал вместе со связью - ровно в тот момент, когда
 * становился нужен.
 */
@Composable
private fun ArchivePanel(viewModel: ObdViewModel, offline: Boolean) {
    val archive by viewModel.archive.collectAsState()
    val context = LocalContext.current

    Panel {
        PanelTitle(stringResource(R.string.archive_title))
        if (archive.isEmpty()) {
            Hint(stringResource(R.string.archive_empty))
            return@Panel
        }
        Hint(stringResource(R.string.archive_hint))
        archive.forEachIndexed { index, snapshot ->
            // Пока машина на связи, свежая запись - это ровно то, что уже
            // показано живым списком выше; разворачивать её значит показать
            // одно и то же дважды. Без связи наоборот: разворачиваем, ради
            // этого архив и делался.
            ArchiveEntry(snapshot, expandedByDefault = offline && index == 0) {
                val text = DtcArchive.asText(snapshot, archiveDate(snapshot.timeMillis))
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        },
                        null
                    )
                )
            }
        }
        TextButton(onClick = viewModel::clearArchive) {
            Text(stringResource(R.string.archive_clear), color = GaugeAmber)
        }
    }
}

@Composable
private fun ArchiveEntry(snapshot: DtcSnapshot, expandedByDefault: Boolean, onShare: () -> Unit) {
    var expanded by remember(snapshot.timeMillis) { mutableStateOf(expandedByDefault) }

    Column(Modifier.padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    archiveDate(snapshot.timeMillis),
                    color = TextBright,
                    style = MaterialTheme.typography.titleMedium
                )
                Hint(
                    if (snapshot.isEmpty) {
                        stringResource(R.string.archive_no_codes)
                    } else {
                        stringResource(R.string.archive_count, snapshot.total)
                    }
                )
            }
            TextButton(onClick = onShare) {
                Text(stringResource(R.string.archive_share), color = TextBright)
            }
        }

        if (!expanded) return@Column

        Hint(snapshot.vehicle)
        snapshot.vin?.let { Hint("VIN $it") }
        ArchiveCodes(stringResource(R.string.dtc_group_stored), snapshot.stored)
        ArchiveCodes(stringResource(R.string.dtc_group_pending), snapshot.pending)
        ArchiveCodes(stringResource(R.string.dtc_group_permanent), snapshot.permanent)
        if (snapshot.modules.isNotEmpty()) {
            Text(
                stringResource(R.string.archive_modules),
                color = TextMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
            snapshot.modules.forEach { module ->
                // Расшифровка берётся из каталога, а он работает без машины -
                // в этом и смысл архива.
                ArchiveCodeRow("${module.hex}: ${module.code}", module.code.substringBefore('-'))
            }
        }
    }
}

@Composable
private fun ArchiveCodes(title: String, codes: List<String>) {
    if (codes.isEmpty()) return
    Text(
        title,
        color = TextMuted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 6.dp)
    )
    codes.forEach { ArchiveCodeRow(it, it) }
}

@Composable
private fun ArchiveCodeRow(label: String, code: String) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(label, color = TextBright, style = MaterialTheme.typography.titleSmall)
        Text(Dtc(code).description, color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Дата чтения человеческим текстом - по ней его и будут искать. */
private fun archiveDate(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))

@Composable
private fun OwnDescriptionDialog(
    code: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(code) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dtc_own_title, code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(stringResource(R.string.dtc_own_hint))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.dtc_own_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.dtc_own_save)) }
        },
        dismissButton = {
            Row {
                if (initial.isNotEmpty()) {
                    TextButton(onClick = { onSave("") }) {
                        Text(stringResource(R.string.dtc_own_delete), color = GaugeRed)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dtc_cancel)) }
            }
        }
    )
}

private fun severityColor(severity: DtcSeverity): Color = when (severity) {
    DtcSeverity.CRITICAL -> GaugeRed
    DtcSeverity.WARNING -> GaugeAmber
    DtcSeverity.INFO -> GaugeGreen
}
