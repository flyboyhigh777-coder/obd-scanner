package com.obdscanner.core.obd

/**
 * Какие PID вообще поддерживает машина. Спрашивается не по одному - есть
 * специальные PID-"карты": 0x00 отвечает битовой маской для PID 0x01..0x20,
 * 0x20 - для 0x21..0x40 и так далее. Старший бит первого байта = первый PID
 * диапазона.
 *
 * Если в маске выставлен бит самого последнего PID диапазона (0x20, 0x40, ...),
 * значит есть и следующая карта - только тогда её имеет смысл запрашивать, иначе
 * на каждом опросе уходит 6 лишних запросов по 50-200 мс.
 */
object PidSupport {

    /** Карты поддержки по порядку. */
    val RANGE_PIDS: List<Int> = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    /**
     * @param base номер карты (0x00, 0x20, ...).
     * @param data 4 байта ответа.
     */
    fun decode(base: Int, data: IntArray): Set<Int> {
        if (data.size < 4) return emptySet()
        val out = LinkedHashSet<Int>()
        for (i in 0 until 4) {
            for (k in 0 until 8) {
                if ((data[i] shr (7 - k)) and 1 == 1) out.add(base + i * 8 + k + 1)
            }
        }
        return out
    }

    /**
     * Обратная операция - собрать 4 байта маски из набора PID. Нужна симулятору
     * ЭБУ и тестам: так проверяется, что декодер и кодировщик согласованы.
     */
    fun encode(base: Int, pids: Set<Int>): IntArray {
        val out = IntArray(4)
        for (pid in pids) {
            val offset = pid - base - 1
            if (offset < 0 || offset >= 32) continue
            out[offset / 8] = out[offset / 8] or (1 shl (7 - offset % 8))
        }
        return out
    }

    /** Есть ли смысл спрашивать следующую карту после [base]. */
    fun hasNextRange(base: Int, supported: Set<Int>): Boolean = (base + 0x20) in supported
}

/**
 * Статус бортовых тестов (PID 0x01) - то, что стоит за лампой Check Engine:
 * горит ли она, сколько сохранённых ошибок и какие самодиагностики ЭБУ успел
 * прогнать. Последнее важно перед техосмотром: пока тесты "не завершены",
 * машину не примут, даже если ошибок нет.
 */
data class MonitorStatus(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<Monitor>
) {
    data class Monitor(val ru: String, val en: String, val supported: Boolean, val complete: Boolean)

    /** Тесты, которые машина поддерживает, но ещё не прогнала. */
    val incomplete: List<Monitor> get() = monitors.filter { it.supported && !it.complete }

    val readyForInspection: Boolean get() = incomplete.isEmpty()

    companion object {
        // Общие для бензина и дизеля тесты (байт B).
        private val COMMON = listOf(
            Triple("Пропуски зажигания", "Misfire", 0),
            Triple("Топливная система", "Fuel system", 1),
            Triple("Компоненты", "Components", 2)
        )

        // Байты C (поддерживается) и D (завершён) - бензин.
        private val SPARK = listOf(
            Triple("Катализатор", "Catalyst", 0),
            Triple("Подогрев катализатора", "Heated catalyst", 1),
            Triple("Система улавливания паров", "Evaporative system", 2),
            Triple("Система вторичного воздуха", "Secondary air system", 3),
            Triple("Хладагент кондиционера", "A/C refrigerant", 4),
            Triple("Лямбда-зонды", "Oxygen sensor", 5),
            Triple("Подогрев лямбда-зондов", "Oxygen sensor heater", 6),
            Triple("Система EGR", "EGR system", 7)
        )

        // Те же байты, но у дизеля набор тестов другой.
        private val COMPRESSION = listOf(
            Triple("Катализатор NMHC", "NMHC catalyst", 0),
            Triple("Система NOx/SCR", "NOx/SCR aftertreatment", 1),
            Triple("Давление наддува", "Boost pressure", 3),
            Triple("Датчики отработавших газов", "Exhaust gas sensor", 5),
            Triple("Сажевый фильтр", "PM filter", 6),
            Triple("EGR / VVT", "EGR and/or VVT", 7)
        )

        fun decode(data: IntArray): MonitorStatus? {
            if (data.size < 4) return null
            val a = data[0]; val b = data[1]; val c = data[2]; val d = data[3]
            val compression = (b shr 3) and 1 == 1
            val monitors = ArrayList<MonitorStatus.Monitor>()
            for ((ru, en, bit) in COMMON) {
                // В байте B биты 0-2 - "тест поддерживается", биты 4-6 - "не завершён".
                monitors.add(Monitor(ru, en, (b shr bit) and 1 == 1, (b shr (bit + 4)) and 1 == 0))
            }
            for ((ru, en, bit) in if (compression) COMPRESSION else SPARK) {
                monitors.add(Monitor(ru, en, (c shr bit) and 1 == 1, (d shr bit) and 1 == 0))
            }
            return MonitorStatus(
                milOn = (a shr 7) and 1 == 1,
                dtcCount = a and 0x7F,
                compressionIgnition = compression,
                monitors = monitors
            )
        }
    }
}
