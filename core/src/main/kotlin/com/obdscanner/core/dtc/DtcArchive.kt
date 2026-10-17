package com.obdscanner.core.dtc

import com.obdscanner.core.elm.ObdText
import com.obdscanner.core.modules.ModuleScan
import com.obdscanner.core.modules.UdsStatus

/**
 * Прочитанные ошибки, которые остаются после отключения от машины.
 *
 * Зачем это нужно. Коды читают в гараже или на обочине, с телефоном в одной
 * руке, а разбираются с ними потом - дома, за компьютером, с поиском. Пока
 * список жил только в памяти приложения, он пропадал в тот самый момент, когда
 * становился нужен: вынул адаптер - и ищи заново, снова подключайся к машине.
 *
 * Формат нарочно текстовый и построчный: его можно открыть глазами, отправить
 * в мессенджер и прочитать на другом конце без всякого приложения. Разбор
 * устроен так, чтобы непонятная строка пропускалась, а не роняла весь архив:
 * файл старой версии должен открываться новым приложением, а лишние столбцы
 * новой версии старое приложение просто не читает.
 */

/**
 * Код из блока вне OBD-II в том виде, в каком его сохранили.
 *
 * [status] - байт статуса UDS: без него по архиву не понять, была ли ошибка
 * настоящей или это запись о тесте, который ещё не проходил. У записей старых
 * версий его нет.
 */
data class ArchivedModuleDtc(val address: Int, val code: String, val status: Int? = null) {
    val hex: String get() = "%03X".format(address)

    /** Код без уточнения отказа: по нему ищется расшифровка. */
    val baseCode: String get() = code.substringBefore('-')

    val statusText: String? get() = UdsStatus.describe(status)
}

/**
 * Одно чтение: что нашлось за одно подключение к машине.
 *
 * Снимок обновляется по ходу сеанса - прочитали коды двигателя, потом
 * прошлись по блокам, и всё это остаётся одной записью, а не тремя.
 *
 * [descriptions] - расшифровки в момент чтения (код без "-XX" -> текст). Они
 * пишутся в файл, чтобы архив был понятен и без приложения, и чтобы запись
 * осталась читаемой, даже если справочник ещё не успел загрузиться.
 *
 * [beforeClear] - снимок сделан непосредственно перед сбросом ошибок. После
 * сброса этих кодов в машине больше нет, и архив - единственное место, где
 * они остались.
 */
data class DtcSnapshot(
    val timeMillis: Long,
    val vehicle: String,
    val vin: String? = null,
    val stored: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val permanent: List<String> = emptyList(),
    val modules: List<ArchivedModuleDtc> = emptyList(),
    val milOn: Boolean = false,
    val descriptions: Map<String, String> = emptyMap(),
    val beforeClear: Boolean = false,
) {
    val total: Int get() = stored.size + pending.size + permanent.size + modules.size

    val isEmpty: Boolean get() = total == 0

    /** Все коды записи без уточнения отказа - для расшифровок. */
    val allBaseCodes: Set<String>
        get() = (stored + pending + permanent + modules.map { it.baseCode })
            .map { it.substringBefore('-').uppercase() }
            .toCollection(LinkedHashSet())

    /**
     * Расшифровка для показа.
     *
     * Если справочник знает код точно - берём его: он мог стать лучше с
     * момента чтения (человек вписал свою расшифровку, обновилась таблица
     * блока). Иначе - то, что сохранилось при чтении, а если не сохранилось
     * ничего (запись старой версии), то хотя бы описание семейства.
     */
    fun describe(code: String): String {
        val base = code.substringBefore('-').uppercase()
        val saved = descriptions[base]?.takeIf { it.isNotBlank() }
        if (saved == null || DtcCatalog.hasExactDescription(base)) return DtcCatalog.describe(base)
        return saved
    }
}

object DtcArchive {

    /**
     * Сколько чтений храним. Десятка хватает, чтобы сравнить "до ремонта" и
     * "после", а дальше список перестаёт быть обозримым.
     */
    const val MAX_SNAPSHOTS = 10

    private const val RECORD = '='
    private const val SEP = '\t'
    private const val KIND_BEFORE_CLEAR = "clear"

    /**
     * Текст архива. Новые записи идут первыми - в таком же порядке их потом и
     * показывают.
     *
     * Заголовок: `=время<TAB>машина<TAB>VIN<TAB>лампа<TAB>вид`. Коды:
     * `S|P|A<TAB>код<TAB>расшифровка` и
     * `M<TAB>адрес<TAB>код<TAB>статус hex<TAB>расшифровка`. Последние столбцы
     * добавлены позже первых - старое приложение их просто не читает.
     */
    fun encode(snapshots: List<DtcSnapshot>): String = buildString {
        for (snapshot in snapshots.take(MAX_SNAPSHOTS)) {
            append(RECORD)
            append(snapshot.timeMillis)
            append(SEP).append(clean(snapshot.vehicle))
            append(SEP).append(clean(snapshot.vin.orEmpty()))
            append(SEP).append(if (snapshot.milOn) "1" else "0")
            append(SEP).append(if (snapshot.beforeClear) KIND_BEFORE_CLEAR else "")
            append('\n')
            fun text(code: String) = clean(snapshot.descriptions[code.substringBefore('-').uppercase()].orEmpty())
            snapshot.stored.forEach { appendCode("S", it, text(it)) }
            snapshot.pending.forEach { appendCode("P", it, text(it)) }
            snapshot.permanent.forEach { appendCode("A", it, text(it)) }
            for (module in snapshot.modules) {
                append("M").append(SEP).append(module.hex).append(SEP)
                append(clean(module.code)).append(SEP)
                append(module.status?.let { "%02X".format(it and 0xFF) }.orEmpty()).append(SEP)
                append(text(module.code)).append('\n')
            }
        }
    }

    private fun StringBuilder.appendCode(kind: String, code: String, text: String) {
        append(kind).append(SEP).append(clean(code)).append(SEP).append(text).append('\n')
    }

    /** Разбор. Непонятные строки пропускаются - архив старой версии не должен ломаться. */
    fun decode(text: String): List<DtcSnapshot> {
        val out = ArrayList<DtcSnapshot>()
        var time = 0L
        var vehicle = ""
        var vin: String? = null
        var mil = false
        var beforeClear = false
        var stored = ArrayList<String>()
        var pending = ArrayList<String>()
        var permanent = ArrayList<String>()
        var modules = ArrayList<ArchivedModuleDtc>()
        var descriptions = LinkedHashMap<String, String>()
        var open = false

        fun flush() {
            if (!open) return
            out.add(
                DtcSnapshot(
                    timeMillis = time,
                    vehicle = vehicle,
                    vin = vin,
                    stored = stored.toList(),
                    pending = pending.toList(),
                    permanent = permanent.toList(),
                    modules = modules.toList(),
                    milOn = mil,
                    descriptions = descriptions.toMap(),
                    beforeClear = beforeClear,
                )
            )
            stored = ArrayList()
            pending = ArrayList()
            permanent = ArrayList()
            modules = ArrayList()
            descriptions = LinkedHashMap()
            open = false
        }

        fun remember(code: String, text: String?) {
            val value = text?.trim().orEmpty()
            if (value.isEmpty()) return
            descriptions.putIfAbsent(code.substringBefore('-').uppercase(), value)
        }

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            if (line[0] == RECORD) {
                flush()
                val parts = line.drop(1).split(SEP)
                time = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: continue
                vehicle = parts.getOrNull(1).orEmpty()
                vin = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                mil = parts.getOrNull(3)?.trim() == "1"
                beforeClear = parts.getOrNull(4)?.trim() == KIND_BEFORE_CLEAR
                open = true
                continue
            }
            if (!open) continue
            val parts = line.split(SEP)
            val code = parts.getOrNull(1)?.trim().orEmpty()
            when (parts[0]) {
                "S" -> if (code.isNotEmpty()) { stored.add(code); remember(code, parts.getOrNull(2)) }
                "P" -> if (code.isNotEmpty()) { pending.add(code); remember(code, parts.getOrNull(2)) }
                "A" -> if (code.isNotEmpty()) { permanent.add(code); remember(code, parts.getOrNull(2)) }
                "M" -> {
                    val address = code.toIntOrNull(16)
                    val moduleCode = parts.getOrNull(2)?.trim().orEmpty()
                    if (address != null && moduleCode.isNotEmpty()) {
                        val status = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull(16)
                        modules.add(ArchivedModuleDtc(address, moduleCode, status))
                        remember(moduleCode, parts.getOrNull(4))
                    }
                }
            }
        }
        flush()
        return out
    }

    /**
     * Дописывает в снимок расшифровки из справочника - для тех кодов, у
     * которых их ещё нет. Делается в момент чтения: что человек видел на
     * экране, то и ляжет в архив.
     */
    fun withDescriptions(snapshot: DtcSnapshot): DtcSnapshot {
        val texts = LinkedHashMap(snapshot.descriptions)
        for (code in snapshot.allBaseCodes) {
            if (texts[code].isNullOrBlank()) texts[code] = DtcCatalog.describe(code)
        }
        return snapshot.copy(descriptions = texts)
    }

    /**
     * Добавляет или обновляет запись.
     *
     * Одно подключение - одна запись: коды двигателя и обход блоков делаются
     * по очереди, и разносить их по разным строкам архива значит заставлять
     * человека складывать их обратно глазами. Запись с тем же временем
     * заменяется, остальные сдвигаются.
     *
     * [replacing] - время записи, которую новая заменяет целиком. Так снимок
     * перед сбросом встаёт на место записи сеанса: в нём то же самое плюс
     * свежее чтение, и держать обе - значит показывать одни коды дважды.
     */
    fun merge(existing: List<DtcSnapshot>, snapshot: DtcSnapshot, replacing: Long? = null): List<DtcSnapshot> {
        if (snapshot.isEmpty) return existing.take(MAX_SNAPSHOTS)
        val rest = existing.filter { it.timeMillis != snapshot.timeMillis && it.timeMillis != replacing }
        return (listOf(snapshot) + rest)
            .sortedByDescending { it.timeMillis }
            .take(MAX_SNAPSHOTS)
    }

    /**
     * Текст для отправки в мессенджер: то, с чем человек пойдёт искать.
     *
     * Каждый код - с расшифровкой: голый "P0500-00" в Telegram ничего не
     * говорит, и ошибку спидометра в таком списке просто не узнать.
     */
    fun asText(snapshot: DtcSnapshot, date: String): String = buildString {
        append(snapshot.vehicle).append(", ").append(date).append('\n')
        if (snapshot.beforeClear) {
            append(ObdText.pick("Снято перед сбросом ошибок", "Taken right before clearing codes")).append('\n')
        }
        snapshot.vin?.let { append("VIN ").append(it).append('\n') }
        if (snapshot.milOn) append(ObdText.pick("Check Engine горел", "Check Engine was on")).append('\n')

        fun section(title: String, codes: List<String>) {
            if (codes.isEmpty()) return
            append('\n').append(title).append(":\n")
            codes.forEach { append(it).append(" — ").append(snapshot.describe(it)).append('\n') }
        }
        section(ObdText.pick("Сохранённые", "Stored"), snapshot.stored)
        section(ObdText.pick("Ожидающие", "Pending"), snapshot.pending)
        section(ObdText.pick("Постоянные", "Permanent"), snapshot.permanent)

        for ((address, codes) in snapshot.modules.groupBy { it.address }) {
            append('\n').append(moduleTitle(address)).append(":\n")
            for (module in codes) {
                append(module.code).append(" — ").append(snapshot.describe(module.code))
                module.statusText?.let { append(" [").append(it).append(']') }
                append('\n')
            }
        }
        if (snapshot.isEmpty) append(ObdText.pick("Ошибок нет", "No faults"))
    }.trimEnd()

    /** "Блок 7E0 (двигатель)" - одинаково в приложении, в тексте и в PDF. */
    fun moduleTitle(address: Int): String {
        val hex = "%03X".format(address)
        val label = ModuleScan.label(address)
        return ObdText.pick("Блок ", "Module ") + hex + (label?.let { " ($it)" } ?: "")
    }

    /** Табуляции и переводы строк ломают формат - в кодах их быть не может. */
    private fun clean(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
