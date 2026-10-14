package com.obdscanner.core.dtc

import com.obdscanner.core.elm.ObdText

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
 * файл старой версии должен открываться новым приложением.
 */

/** Код из блока вне OBD-II в том виде, в каком его сохранили. */
data class ArchivedModuleDtc(val address: Int, val code: String) {
    val hex: String get() = "%03X".format(address)
}

/**
 * Одно чтение: что нашлось за одно подключение к машине.
 *
 * Снимок обновляется по ходу сеанса - прочитали коды двигателя, потом
 * прошлись по блокам, и всё это остаётся одной записью, а не тремя.
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
) {
    val total: Int get() = stored.size + pending.size + permanent.size + modules.size

    val isEmpty: Boolean get() = total == 0
}

object DtcArchive {

    /**
     * Сколько чтений храним. Десятка хватает, чтобы сравнить "до ремонта" и
     * "после", а дальше список перестаёт быть обозримым.
     */
    const val MAX_SNAPSHOTS = 10

    private const val RECORD = '='
    private const val SEP = '\t'

    /**
     * Текст архива. Новые записи идут первыми - в таком же порядке их потом и
     * показывают.
     */
    fun encode(snapshots: List<DtcSnapshot>): String = buildString {
        for (snapshot in snapshots.take(MAX_SNAPSHOTS)) {
            append(RECORD)
            append(snapshot.timeMillis)
            append(SEP).append(clean(snapshot.vehicle))
            append(SEP).append(clean(snapshot.vin.orEmpty()))
            append(SEP).append(if (snapshot.milOn) "1" else "0")
            append('\n')
            snapshot.stored.forEach { append("S").append(SEP).append(clean(it)).append('\n') }
            snapshot.pending.forEach { append("P").append(SEP).append(clean(it)).append('\n') }
            snapshot.permanent.forEach { append("A").append(SEP).append(clean(it)).append('\n') }
            for (module in snapshot.modules) {
                append("M").append(SEP).append(module.hex).append(SEP)
                append(clean(module.code)).append('\n')
            }
        }
    }

    /** Разбор. Непонятные строки пропускаются - архив старой версии не должен ломаться. */
    fun decode(text: String): List<DtcSnapshot> {
        val out = ArrayList<DtcSnapshot>()
        var time = 0L
        var vehicle = ""
        var vin: String? = null
        var mil = false
        var stored = ArrayList<String>()
        var pending = ArrayList<String>()
        var permanent = ArrayList<String>()
        var modules = ArrayList<ArchivedModuleDtc>()
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
                )
            )
            stored = ArrayList()
            pending = ArrayList()
            permanent = ArrayList()
            modules = ArrayList()
            open = false
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
                open = true
                continue
            }
            if (!open) continue
            val parts = line.split(SEP)
            val code = parts.getOrNull(1)?.trim().orEmpty()
            when (parts[0]) {
                "S" -> if (code.isNotEmpty()) stored.add(code)
                "P" -> if (code.isNotEmpty()) pending.add(code)
                "A" -> if (code.isNotEmpty()) permanent.add(code)
                "M" -> {
                    val address = code.toIntOrNull(16)
                    val moduleCode = parts.getOrNull(2)?.trim().orEmpty()
                    if (address != null && moduleCode.isNotEmpty()) {
                        modules.add(ArchivedModuleDtc(address, moduleCode))
                    }
                }
            }
        }
        flush()
        return out
    }

    /**
     * Добавляет или обновляет запись.
     *
     * Одно подключение - одна запись: коды двигателя и обход блоков делаются
     * по очереди, и разносить их по разным строкам архива значит заставлять
     * человека складывать их обратно глазами. Запись с тем же временем
     * заменяется, остальные сдвигаются.
     */
    fun merge(existing: List<DtcSnapshot>, snapshot: DtcSnapshot): List<DtcSnapshot> {
        if (snapshot.isEmpty) return existing.take(MAX_SNAPSHOTS)
        val rest = existing.filter { it.timeMillis != snapshot.timeMillis }
        return (listOf(snapshot) + rest)
            .sortedByDescending { it.timeMillis }
            .take(MAX_SNAPSHOTS)
    }

    /** Текст для отправки в мессенджер: то, с чем человек пойдёт искать. */
    fun asText(snapshot: DtcSnapshot, date: String): String = buildString {
        append(snapshot.vehicle).append(", ").append(date).append('\n')
        snapshot.vin?.let { append("VIN ").append(it).append('\n') }
        if (snapshot.stored.isNotEmpty()) {
            append(ObdText.pick("Сохранённые: ", "Stored: "))
            append(snapshot.stored.joinToString(", ")).append('\n')
        }
        if (snapshot.pending.isNotEmpty()) {
            append(ObdText.pick("Ожидающие: ", "Pending: "))
            append(snapshot.pending.joinToString(", ")).append('\n')
        }
        if (snapshot.permanent.isNotEmpty()) {
            append(ObdText.pick("Постоянные: ", "Permanent: "))
            append(snapshot.permanent.joinToString(", ")).append('\n')
        }
        for (module in snapshot.modules) {
            append(ObdText.pick("Блок ", "Module "))
            append(module.hex).append(": ").append(module.code).append('\n')
        }
        if (snapshot.isEmpty) append(ObdText.pick("Ошибок нет", "No faults"))
    }

    /** Табуляции и переводы строк ломают формат - в кодах их быть не может. */
    private fun clean(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
