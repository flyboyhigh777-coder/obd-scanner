package com.obdscanner.core.obd

import com.obdscanner.core.elm.ObdText

/**
 * Поиск заводских параметров перебором (режим 22).
 *
 * Зачем это нужно. Стандарт описывает полторы сотни параметров, но каждый
 * производитель добавляет свои, и списка этих номеров в открытом виде обычно
 * нет - для ME17.9.7/ME17.9.71 его найти не удалось ни в открытых базах, ни на
 * профильных сайтах. Зато сам блок управления честно отвечает на запрос: если
 * параметра нет, приходит отказ, если есть - значение.
 *
 * Поэтому номер, которого нет в документации, ищется не чтением форумов, а
 * опросом конкретной машины: перебрать диапазон, посмотреть, что ответило, а
 * потом включить фары или обогрев стекла и посмотреть, что изменилось. Тот
 * параметр, который растёт вместе с нагрузкой, и есть искомый.
 *
 * Перебор только читает. Запись (режим 2E) приложение не делает нигде и
 * никогда: оборванная запись превращает блок в кирпич.
 */

/** Ответивший параметр: номер и сырые байты. */
data class ScanHit(val pid: Int, val bytes: List<Int>) {

    /** Номер в том виде, в каком его пишут в запросе: "221148". */
    val request: String get() = "22%04X".format(pid)

    val label: String get() = "%04X".format(pid)

    val hex: String get() = bytes.joinToString(" ") { "%02X".format(it) }

    /**
     * Байты как одно беззнаковое число - этого хватает, чтобы заметить
     * изменение. Что там на самом деле (температура, проценты, ток), без
     * документации не скажет никто: формулу человек подбирает сам.
     */
    val value: Long get() = bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
}

/** Параметр, значение которого изменилось между двумя снимками. */
data class ScanChange(val pid: Int, val before: ScanHit, val after: ScanHit) {

    val label: String get() = "%04X".format(pid)

    val delta: Long get() = after.value - before.value
}

/** Диапазон перебора с человеческим названием. */
data class ScanRange(val from: Int, val to: Int, val ru: String, val en: String) {

    val title: String get() = ObdText.pick(ru, en)

    val size: Int get() = (to - from + 1).coerceAtLeast(0)
}

object PidScan {

    /**
     * Весь диапазон - это 65 536 запросов, и на живом адаптере это часы.
     * Поэтому предлагаются куски, где заводские параметры встречаются чаще
     * всего. Если в них пусто, диапазон можно задать руками.
     */
    val PRESETS: List<ScanRange> = listOf(
        ScanRange(0xF400, 0xF4FF, "F400-F4FF: зеркало стандартных PID", "F400-F4FF: standard PID mirror"),
        ScanRange(0x0100, 0x01FF, "0100-01FF", "0100-01FF"),
        ScanRange(0x0200, 0x02FF, "0200-02FF", "0200-02FF"),
        ScanRange(0x1100, 0x11FF, "1100-11FF", "1100-11FF"),
    )

    /** Больше за один проход не перебираем: иначе это десятки минут. */
    const val MAX_SPAN = 4096

    fun clampRange(from: Int, to: Int): ScanRange {
        val lo = from.coerceIn(0, 0xFFFF)
        val hi = to.coerceIn(lo, 0xFFFF).coerceAtMost(lo + MAX_SPAN - 1)
        return ScanRange(lo, hi, "%04X-%04X".format(lo, hi), "%04X-%04X".format(lo, hi))
    }

    /**
     * Что изменилось между снимками.
     *
     * Сравниваются только параметры, которые ответили в обоих снимках: если
     * во втором параметр пропал, это почти наверняка потерянный ответ, а не
     * осмысленное изменение, и показывать его как находку - только путать.
     */
    fun compare(before: List<ScanHit>, after: List<ScanHit>): List<ScanChange> {
        val old = before.associateBy { it.pid }
        return after.mapNotNull { now ->
            val was = old[now.pid] ?: return@mapNotNull null
            if (was.bytes == now.bytes) null else ScanChange(now.pid, was, now)
        }.sortedByDescending { kotlin.math.abs(it.delta) }
    }
}
