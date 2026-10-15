package com.obdscanner.core.diag

import com.obdscanner.core.dtc.Dtc
import com.obdscanner.core.dtc.DtcSource
import com.obdscanner.core.elm.AdapterInfo
import com.obdscanner.core.elm.ObdProtocol
import com.obdscanner.core.elm.ObdText
import com.obdscanner.core.monitor.MonitorTest
import com.obdscanner.core.obd.InUsePerformance
import com.obdscanner.core.obd.FuelSystemStatus
import com.obdscanner.core.obd.MonitorStatus
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.obd.VinDecoder
import com.obdscanner.core.obd.VinInfo
import com.obdscanner.core.session.ObdSession

/** Этап полной проверки - приложение показывает его строкой и полосой прогресса. */
enum class QuickCheckStep(val ru: String, val en: String) {
    ADAPTER("Адаптер", "Adapter"),
    VEHICLE("Данные автомобиля", "Vehicle data"),
    STATUS("Статус бортовых тестов", "Monitor status"),
    DTC("Коды неисправностей", "Trouble codes"),
    FREEZE_FRAME("Стоп-кадр", "Freeze frame"),
    MONITOR_TESTS("Результаты бортовых тестов", "On-board test results"),
    PERFORMANCE("Счётчики наработки тестов", "In-use performance"),
    LIVE("Текущие параметры", "Live data"),
    DONE("Готово", "Done");

    val title: String get() = ObdText.pick(ru, en)
}

/**
 * Результат полной проверки машины - всё, что можно узнать, не трогая ЭБУ на
 * запись, в одном снимке.
 *
 * Смысл такого отчёта не в том, чтобы собрать всё в одном месте, а в том, что
 * диагностика по телефону - это всегда "посмотрел один экран, уехал, забыл".
 * Отчёт можно отдать в сервис, приложить к объявлению о продаже или просто
 * сравнить с собой же месяц назад: пробег с горящей лампой, число сбросов,
 * счётчики наработки тестов - именно то, что на словах не передать.
 */
data class QuickCheckReport(
    val startedAtMillis: Long,
    val adapter: AdapterInfo? = null,
    /** Как адаптер представился при сбросе - на случай, если проверку не делали. */
    val adapterId: String = "",
    val protocol: ObdProtocol? = null,
    val adapterVoltage: Double? = null,
    val vin: String? = null,
    val ecuName: String? = null,
    val calibrationIds: List<String> = emptyList(),
    val calibrationVerificationNumbers: List<String> = emptyList(),
    val supportedPidCount: Int = 0,
    val monitorStatus: MonitorStatus? = null,
    val stored: List<Dtc> = emptyList(),
    val pending: List<Dtc> = emptyList(),
    val permanent: List<Dtc> = emptyList(),
    val freezeFrameDtc: String? = null,
    val freezeFrame: Map<Int, Double> = emptyMap(),
    val monitorTests: List<MonitorTest> = emptyList(),
    val performance: InUsePerformance? = null,
    val live: Map<Int, Double> = emptyMap(),
    /** Что прочитать не удалось - в отчёте это так и написано, а не опущено. */
    val skipped: List<String> = emptyList()
) {
    val vinInfo: VinInfo? get() = vin?.let { VinDecoder.decode(it) }

    val dtcTotal: Int get() = stored.size + pending.size + permanent.size

    val failedTests: List<MonitorTest> get() = monitorTests.filter { it.passed == false }

    /**
     * Короткий вывод: именно он отвечает на вопрос "ехать можно?".
     *
     * Постоянные коды здесь весомее сохранённых: сохранённый код можно стереть
     * кнопкой, постоянный гасит только сам блок, убедившись, что неисправности
     * больше нет.
     */
    val verdict: String
        get() = when {
            monitorStatus?.milOn == true -> ObdText.pick(
                "Check Engine горит, ошибок: $dtcTotal",
                "MIL is on, $dtcTotal trouble code(s)"
            )
            permanent.isNotEmpty() -> ObdText.pick(
                "Лампа не горит, но есть постоянные коды - блок ещё не убедился в исправности",
                "MIL off, but permanent codes remain - the ECU is not convinced yet"
            )
            dtcTotal > 0 -> ObdText.pick(
                "Лампа не горит, но коды записаны: $dtcTotal",
                "MIL off, $dtcTotal stored code(s)"
            )
            monitorStatus?.readyForInspection == false -> ObdText.pick(
                "Ошибок нет, но бортовые тесты не завершены - на техосмотр рано",
                "No codes, but monitors are not complete - too early for inspection"
            )
            monitorStatus != null -> ObdText.pick("Ошибок нет, тесты завершены", "No codes, monitors complete")
            else -> ObdText.pick("Данных недостаточно", "Not enough data")
        }

    /** Отчёт текстом - его и отдают в сервис или прикладывают к переписке. */
    /**
     * Отчёт разобранными блоками: заголовок, разделы, строки.
     *
     * Так он существует в одном виде, а показывается в двух - текстом и
     * страницами PDF. Отрисовывать отчёт дважды, по разу на формат, значит
     * однажды поправить одно и забыть другое: в PDF появится то, чего нет в
     * тексте, и наоборот.
     */
    fun blocks(): List<ReportBlock> {
        val ru = ObdText.russian
        val noData = ObdText.pick("нет данных", "no data")
        val out = ArrayList<ReportBlock>()

        fun row(label: String, value: String?, tone: ReportTone = ReportTone.PLAIN) {
            out.add(ReportBlock.Row(label, value?.takeIf { it.isNotBlank() } ?: noData, tone))
        }

        fun section(title: String) {
            out.add(ReportBlock.Space)
            out.add(ReportBlock.Section(title))
        }

        out.add(
            ReportBlock.Title(
                if (ru) "ОТЧЁТ ДИАГНОСТИКИ OBD-II" else "OBD-II DIAGNOSTIC REPORT",
                subtitle = listOfNotNull(timestampText(), vin).joinToString(" · ")
            )
        )
        out.add(
            ReportBlock.Row(
                if (ru) "Вывод" else "Verdict",
                verdict,
                if (dtcTotal > 0 || failedTests.isNotEmpty()) ReportTone.BAD else ReportTone.GOOD
            )
        )

        section(if (ru) "Связь" else "Connection")
        row(if (ru) "Протокол" else "Protocol", protocol?.title)
        row(if (ru) "Адаптер" else "Adapter", adapterName)
        adapter?.let { row(if (ru) "Тип чипа" else "Chip", it.chip.title) }
        row(
            if (ru) "Напряжение на разъёме" else "Socket voltage",
            adapterVoltage?.let { "%.1f %s".format(it, ObdText.pick("В", "V")) }
        )
        row(if (ru) "Параметров поддерживается" else "Supported parameters", supportedPidCount.toString())

        section(if (ru) "Автомобиль" else "Vehicle")
        row("VIN", vin)
        vinInfo?.let {
            row(if (ru) "Страна" else "Country", it.country)
            row(if (ru) "Модельный год" else "Model year", it.modelYear?.toString())
        }
        row(if (ru) "Блок управления" else "ECU name", ecuName)
        calibrationIds.forEachIndexed { i, id -> row("CALID ${i + 1}", id) }
        calibrationVerificationNumbers.forEachIndexed { i, cvn -> row("CVN ${i + 1}", cvn) }

        section(if (ru) "Лампа и ошибки" else "MIL and trouble codes")
        monitorStatus?.let {
            row(
                if (ru) "Check Engine" else "MIL",
                if (it.milOn) (if (ru) "горит" else "on") else (if (ru) "не горит" else "off"),
                if (it.milOn) ReportTone.BAD else ReportTone.GOOD
            )
            row(if (ru) "Кодов в памяти блока" else "Codes in ECU", it.dtcCount.toString())
            row(
                if (ru) "Тип двигателя" else "Engine type",
                if (it.compressionIgnition) (if (ru) "дизель" else "compression ignition")
                else (if (ru) "бензин" else "spark ignition")
            )
        }
        listOf(
            Triple(if (ru) "Сохранённые" else "Stored", stored, ReportTone.BAD),
            Triple(if (ru) "Ожидающие подтверждения" else "Pending", pending, ReportTone.WARN),
            Triple(if (ru) "Постоянные" else "Permanent", permanent, ReportTone.BAD)
        ).forEach { (title, codes, tone) ->
            row(
                title,
                if (codes.isEmpty()) (if (ru) "нет" else "none") else codes.size.toString(),
                if (codes.isEmpty()) ReportTone.GOOD else tone
            )
            codes.forEach { out.add(ReportBlock.Note("${it.code} - ${it.description}", tone)) }
        }

        section(if (ru) "Готовность бортовых тестов" else "Monitor readiness")
        if (monitorStatus == null) {
            out.add(ReportBlock.Note(noData))
        } else {
            monitorStatus.monitors.forEach { monitor ->
                val state = when {
                    !monitor.supported -> if (ru) "не поддерживается" else "not supported"
                    monitor.complete -> if (ru) "завершён" else "complete"
                    else -> if (ru) "НЕ завершён" else "NOT complete"
                }
                row(
                    ObdText.pick(monitor.ru, monitor.en),
                    state,
                    when {
                        !monitor.supported -> ReportTone.PLAIN
                        monitor.complete -> ReportTone.GOOD
                        else -> ReportTone.WARN
                    }
                )
            }
        }

        if (freezeFrameDtc != null || freezeFrame.isNotEmpty()) {
            section(if (ru) "Стоп-кадр" else "Freeze frame")
            row(if (ru) "Код, записавший кадр" else "DTC that caused it", freezeFrameDtc)
            freezeFrame.forEach { (id, value) -> row(pidLabel(id), pidValue(id, value)) }
        }

        if (monitorTests.isNotEmpty()) {
            section(if (ru) "Результаты бортовых тестов (режим 06)" else "On-board test results (mode 06)")
            monitorTests.forEach { test ->
                val tone = when (test.passed) {
                    true -> ReportTone.GOOD
                    false -> ReportTone.BAD
                    null -> ReportTone.PLAIN
                }
                val mark = when (test.passed) {
                    true -> if (ru) "норма" else "pass"
                    false -> if (ru) "ВНЕ ПРЕДЕЛОВ" else "FAIL"
                    null -> if (ru) "пределы не заданы" else "no limits"
                }
                row("${test.title} / ${test.testTitle}", "${test.formatValue()} - $mark", tone)
                val limits = listOfNotNull(
                    test.formatMin()?.let { (if (ru) "мин " else "min ") + it },
                    test.formatMax()?.let { (if (ru) "макс " else "max ") + it }
                ).joinToString(" · ")
                if (limits.isNotEmpty()) out.add(ReportBlock.Note(limits, tone))
            }
        }

        performance?.let { ipt ->
            section(if (ru) "Счётчики наработки тестов" else "In-use performance")
            ipt.obdConditions?.let {
                row(if (ru) "Циклов, пригодных для диагностики" else "OBD monitoring conditions", it.toString())
            }
            ipt.ignitionCycles?.let {
                row(if (ru) "Циклов зажигания" else "Ignition cycles", it.toString())
            }
            // Парами, а не по одному счётчику в строке: само по себе число
            // "завершено: 6" ни о чём не говорит, смысл - в его отношении к
            // числу случаев, когда условия для теста вообще складывались.
            ipt.pairs.forEach { pair ->
                val ratio = pair.ratio
                row(
                    pair.title,
                    if (ratio == null) {
                        if (ru) "условий не было" else "no conditions yet"
                    } else {
                        "${pair.completed.value} ${if (ru) "из" else "of"} ${pair.conditions.value}" +
                            " (${(ratio * 100).toInt()} %)"
                    },
                    // 0,336 - порог, ниже которого бортовая диагностика
                    // считается неработающей по европейским правилам.
                    if (ratio != null && ratio < 0.336) ReportTone.WARN else ReportTone.PLAIN
                )
            }
        }

        if (live.isNotEmpty()) {
            section(if (ru) "Текущие параметры" else "Live data")
            live.forEach { (id, value) -> row(pidLabel(id), pidValue(id, value)) }
        }

        if (skipped.isNotEmpty()) {
            section(if (ru) "Не прочитано" else "Not read")
            skipped.forEach { out.add(ReportBlock.Note(it)) }
        }
        return out
    }

    /** Название адаптера: из проверки, а если её не делали - из ответа на сброс. */
    private val adapterName: String?
        get() = adapter?.description?.takeIf { it.isNotBlank() } ?: adapterId

    private fun pidLabel(id: Int): String = Pids.byId[id]?.title ?: "PID %02X".format(id)

    /**
     * Значение параметра для отчёта. Статус топливной системы - не число, а
     * битовая маска: "2" в отчёте не говорит ничего, "замкнутый контур" -
     * говорит главное.
     */
    private fun pidValue(id: Int, value: Double): String {
        if (id == Pids.FUEL_SYSTEM_STATUS.id) {
            FuelSystemStatus.name(value.toInt())?.let { return it }
        }
        val pid = Pids.byId[id] ?: return value.toString()
        return "${pid.format(value)} ${pid.unit.label}".trim()
    }

    private fun timestampText(): String? = runCatching {
        java.time.Instant.ofEpochMilli(startedAtMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    }.getOrNull()

    /** Отчёт текстом - его отдают в сервис или прикладывают к переписке. */
    fun toText(): String = buildString {
        for (block in blocks()) {
            when (block) {
                is ReportBlock.Title -> {
                    appendLine(block.text)
                    appendLine("=".repeat(32))
                    if (block.subtitle.isNotEmpty()) appendLine(block.subtitle)
                }

                is ReportBlock.Section -> {
                    appendLine(block.text)
                    appendLine("-".repeat(block.text.length))
                }

                is ReportBlock.Row -> appendLine("  ${block.label}: ${block.value}")
                is ReportBlock.Note -> appendLine("    ${block.text}")
                ReportBlock.Space -> appendLine()
            }
        }
    }
}

/**
 * Полная проверка одним нажатием.
 *
 * Порядок не случаен: сначала то, что не зависит от работающего двигателя
 * (ошибки, готовность, VIN), потом то, что осмысленно только на живой машине
 * (текущие параметры). Так отчёт получается полезным, даже если проверку
 * прервали на середине или двигатель не запускали вовсе.
 *
 * Долгие части (режим 06 - это запрос на каждый монитор) можно выключить:
 * [withMonitorTests]. Проверка синхронная и целиком живёт в :core, поэтому
 * прогоняется в тесте против симулятора ЭБУ.
 */
class QuickCheck(
    private val session: ObdSession,
    private val withMonitorTests: Boolean = true,
    /**
     * Готовый результат проверки адаптера, если её уже делали.
     *
     * Повторять её внутри полной проверки нельзя: она задаёт свой заголовок
     * запроса и фильтр приёма, то есть на несколько секунд переводит адаптер в
     * состояние, в котором машину спрашивать нельзя.
     */
    private val adapterInfo: AdapterInfo? = null,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    fun run(
        onStep: (QuickCheckStep) -> Unit = {},
        shouldStop: () -> Boolean = { false }
    ): QuickCheckReport {
        val skipped = ArrayList<String>()
        var report = QuickCheckReport(startedAtMillis = now())

        onStep(QuickCheckStep.ADAPTER)
        report = report.copy(
            adapter = adapterInfo,
            adapterId = session.adapterId,
            protocol = session.protocol,
            adapterVoltage = session.adapterVoltage(),
            supportedPidCount = session.supportedPids.size
        )
        if (shouldStop()) return report

        onStep(QuickCheckStep.VEHICLE)
        val calIds = session.readCalibrationIds()
        report = report.copy(
            vin = session.readVin(),
            ecuName = session.readEcuName(),
            calibrationIds = calIds,
            calibrationVerificationNumbers = session.readCalibrationVerificationNumbers()
        )
        if (report.vin == null) skipped.add(ObdText.pick("VIN: блок не сообщает", "VIN: not reported by the ECU"))
        if (shouldStop()) return report.copy(skipped = skipped)

        onStep(QuickCheckStep.STATUS)
        val status = session.readMonitorStatus()
        report = report.copy(monitorStatus = status)
        if (shouldStop()) return report.copy(skipped = skipped)

        onStep(QuickCheckStep.DTC)
        report = report.copy(
            stored = session.readDtcs(DtcSource.STORED),
            pending = session.readDtcs(DtcSource.PENDING),
            permanent = session.readDtcs(DtcSource.PERMANENT)
        )
        if (shouldStop()) return report.copy(skipped = skipped)

        onStep(QuickCheckStep.FREEZE_FRAME)
        // Стоп-кадр есть только там, где есть сохранённая ошибка: он и
        // записывается вместе с ней. На чистой машине это дюжина запросов, на
        // каждый из которых блок ответит "нет данных", - полминуты впустую.
        val hasStored = report.stored.isNotEmpty() || (status?.dtcCount ?: 0) > 0
        if (hasStored) {
            val freezePids = FREEZE_FRAME_PIDS.mapNotNull { Pids.byId[it] }.filter { session.supports(it.id) }
            report = report.copy(
                freezeFrameDtc = session.readFreezeFrameDtc(),
                freezeFrame = session.readFreezeFrame(freezePids)
            )
        }
        if (shouldStop()) return report.copy(skipped = skipped)

        if (withMonitorTests) {
            onStep(QuickCheckStep.MONITOR_TESTS)
            val tests = session.readAllMonitorTests(shouldStop)
            if (tests.isEmpty()) {
                skipped.add(
                    ObdText.pick(
                        "Режим 06: блок не прислал ни одного результата",
                        "Mode 06: the ECU returned no results"
                    )
                )
            }
            report = report.copy(monitorTests = tests)
            if (shouldStop()) return report.copy(skipped = skipped)
        }

        onStep(QuickCheckStep.PERFORMANCE)
        val performance = session.readInUsePerformance(status?.compressionIgnition == true)
        if (performance == null) {
            skipped.add(
                ObdText.pick(
                    "Счётчики наработки тестов: блок не поддерживает",
                    "In-use performance counters: not supported"
                )
            )
        }
        report = report.copy(performance = performance)
        if (shouldStop()) return report.copy(skipped = skipped)

        onStep(QuickCheckStep.LIVE)
        val livePids = LIVE_PIDS.mapNotNull { Pids.byId[it] }.filter { session.supports(it.id) }
        report = report.copy(live = session.readAll(livePids))

        onStep(QuickCheckStep.DONE)
        return report.copy(skipped = skipped)
    }

    private companion object {
        /** Что показывают в стоп-кадре: обстановка в момент записи ошибки. */
        val FREEZE_FRAME_PIDS = listOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x03, 0x06, 0x07)

        /** Что попадает в отчёт из текущих параметров. */
        val LIVE_PIDS = listOf(
            0x05, 0x0C, 0x0D, 0x04, 0x11, 0x0F, 0x10, 0x42, 0x2F, 0x5C,
            0x06, 0x07, 0x14, 0x15, 0x33, 0x1F, 0x31, 0x30, 0x21, 0x4D
        )
    }
}
