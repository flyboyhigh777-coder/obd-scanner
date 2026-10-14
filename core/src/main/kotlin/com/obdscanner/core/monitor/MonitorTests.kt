package com.obdscanner.core.monitor

import com.obdscanner.core.elm.ObdText

/**
 * Режим 06 - результаты бортовых самодиагностик (on-board monitoring).
 *
 * Это самый недооценённый режим OBD-II и единственный, который показывает
 * состояние узла **до** того, как загорится Check Engine. Режим 01 говорит
 * "датчик жив", режим 03 - "датчик уже умер", а режим 06 даёт число: насколько
 * близко результат теста подошёл к порогу, за которым ЭБУ запишет ошибку.
 * Поэтому по нему и смотрят катализатор, который "пока ещё проходит", и
 * лямбда-зонд, который начал залениваться.
 *
 * Ответ приходит тройками "результат - нижний предел - верхний предел", а что
 * это за величина, говорит идентификатор из таблицы [UnitScalings].
 *
 * Форматов два, и они не похожи:
 *  - **CAN (ISO 15765-4)**: на запрос `06 MID` приходят записи по 9 байт -
 *    номер монитора, номер теста, идентификатор единицы и три двухбайтных
 *    числа. В одном ответе таких записей несколько.
 *  - **старые шины** (J1850, ISO 9141-2, KWP): на запрос `06 TID` приходит
 *    `46 TID CID значение предел` - предел всего один, а какой он, нижний или
 *    верхний, говорит старший бит байта CID. Масштаба в этом формате нет
 *    вообще: числа показываются отсчётами.
 */
data class MonitorTest(
    /** Идентификатор монитора (MID). 0 - старый формат, где его нет. */
    val mid: Int,
    /** Номер теста (TID). */
    val tid: Int,
    /** Идентификатор единицы и масштаба. 0 - масштаба в ответе не было. */
    val uasId: Int,
    val rawValue: Int,
    val rawMin: Int?,
    val rawMax: Int?
) {
    val scaling: UnitScaling? get() = UnitScalings.of(uasId)

    private fun scaled(raw: Int?): Double? {
        val s = scaling ?: return null
        if (raw == null) return null
        val signedRaw = if (s.signed && raw >= 0x8000) raw - 0x10000 else raw
        return s.value(signedRaw)
    }

    val value: Double? get() = scaled(rawValue)
    val min: Double? get() = scaled(rawMin)
    val max: Double? get() = scaled(rawMax)

    /** Единица измерения; пустая строка - величина безразмерная или масштаб неизвестен. */
    val unit: String get() = scaling?.unit.orEmpty()

    /**
     * Прошёл ли тест. Сравниваются сырые отсчёты, а не пересчитанные значения:
     * масштаб у результата и у пределов один и тот же, поэтому пересчёт на
     * сравнение не влияет, а при неизвестном масштабе сравнение всё равно
     * работает.
     *
     * null - сравнивать не с чем: ни одного предела в ответе не было.
     */
    val passed: Boolean?
        get() {
            val valueSigned = signedIfNeeded(rawValue)
            val lo = rawMin?.let(::signedIfNeeded)
            val hi = rawMax?.let(::signedIfNeeded)
            if (lo == null && hi == null) return null
            if (lo != null && valueSigned < lo) return false
            if (hi != null && valueSigned > hi) return false
            return true
        }

    private fun signedIfNeeded(raw: Int): Int =
        if (scaling?.signed == true && raw >= 0x8000) raw - 0x10000 else raw

    /**
     * Насколько результат близок к пределу: 0 - ровно на нижнем пределе, 1 - на
     * верхнем. Нужно шкале на экране: именно это и смотрят, когда проверяют
     * "катализатор пока проходит, но на сколько".
     *
     * null - пределов два не пришло, рисовать нечего.
     */
    val position: Double?
        get() {
            val lo = rawMin?.let(::signedIfNeeded) ?: return null
            val hi = rawMax?.let(::signedIfNeeded) ?: return null
            if (hi == lo) return null
            return (signedIfNeeded(rawValue) - lo).toDouble() / (hi - lo).toDouble()
        }

    /** Название монитора, а если его нет (старый формат) - название теста. */
    val title: String get() = if (mid != 0) MonitorIds.name(mid) else TestIds.name(tid)

    val testTitle: String get() = TestIds.name(tid)

    /** Текст значения с единицей: "0,48 В" либо "123 отсч." при неизвестном масштабе. */
    fun formatValue(): String = format(rawValue, value)

    fun formatMin(): String? = rawMin?.let { format(it, min) }

    fun formatMax(): String? = rawMax?.let { format(it, max) }

    private fun format(raw: Int, scaledValue: Double?): String {
        if (UnitScalings.isBoolean(uasId)) {
            return if (raw != 0) ObdText.pick("да", "true") else ObdText.pick("нет", "false")
        }
        val s = scaling ?: return ObdText.pick("$raw отсч.", "$raw counts")
        val text = "%.${s.decimals}f".format(scaledValue ?: 0.0)
        return if (s.unit.isEmpty()) text else "$text ${s.unit}"
    }

    companion object {
        /** Сколько байт занимает одна запись в ответе по CAN. */
        const val CAN_RECORD_BYTES = 9

        /**
         * Записи из ответа по CAN. [payload] - всё, что идёт после байта 0x46
         * (несколько кадров ISO-TP уже склеены).
         *
         * Хвост, который не дотягивает до целой записи, отбрасывается: ЭБУ
         * добивает последний кадр CAN нулями до восьми байт, и принимать эти
         * нули за тест с нулевым результатом нельзя.
         */
        fun decodeCan(payload: IntArray): List<MonitorTest> {
            val out = ArrayList<MonitorTest>()
            var i = 0
            while (i + CAN_RECORD_BYTES <= payload.size) {
                val mid = payload[i]
                val tid = payload[i + 1]
                // Запись с нулевым монитором - это добивка кадра, а не тест.
                if (mid == 0 && tid == 0) break
                out.add(
                    MonitorTest(
                        mid = mid,
                        tid = tid,
                        uasId = payload[i + 2],
                        rawValue = (payload[i + 3] shl 8) or payload[i + 4],
                        rawMin = (payload[i + 5] shl 8) or payload[i + 6],
                        rawMax = (payload[i + 7] shl 8) or payload[i + 8]
                    )
                )
                i += CAN_RECORD_BYTES
            }
            return out
        }

        /**
         * Запись из ответа на старой шине: `TID CID значение предел`.
         *
         * Старший бит CID говорит, каким пределом является единственное
         * присланное число: 1 - нижним (тест пройден, если результат не меньше),
         * 0 - верхним.
         */
        fun decodeLegacy(payload: IntArray): MonitorTest? {
            if (payload.size < 6) return null
            val tid = payload[0]
            val cid = payload[1]
            val value = (payload[2] shl 8) or payload[3]
            val limit = (payload[4] shl 8) or payload[5]
            val limitIsMinimum = (cid shr 7) and 1 == 1
            return MonitorTest(
                mid = 0,
                tid = tid,
                uasId = 0,
                rawValue = value,
                rawMin = if (limitIsMinimum) limit else null,
                rawMax = if (limitIsMinimum) null else limit
            )
        }
    }
}

/**
 * Какие мониторы (MID) поддерживает ЭБУ. Спрашивается так же, как карты PID:
 * `06 00` отвечает маской для MID 0x01..0x20, `06 20` - для 0x21..0x40 и так
 * далее, и старший бит последнего PID диапазона означает "есть следующая карта".
 */
object MonitorSupport {
    val RANGE_MIDS: List<Int> = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)

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

    fun encode(base: Int, mids: Set<Int>): IntArray {
        val out = IntArray(4)
        for (mid in mids) {
            val offset = mid - base - 1
            if (offset < 0 || offset >= 32) continue
            out[offset / 8] = out[offset / 8] or (1 shl (7 - offset % 8))
        }
        return out
    }

    fun hasNextRange(base: Int, supported: Set<Int>): Boolean = (base + 0x20) in supported
}

/** Названия мониторов (MID) из SAE J1979. */
object MonitorIds {

    private fun bankSensor(ru: String, en: String, index: Int): Pair<String, String> {
        val bank = (index / 4) + 1
        val sensor = (index % 4) + 1
        return "$ru, банк $bank, датчик $sensor" to "$en, bank $bank sensor $sensor"
    }

    private val NAMES: Map<Int, Pair<String, String>> = buildMap {
        // 0x01..0x10 - датчики отработавших газов по банкам и датчикам.
        for (i in 0 until 16) {
            put(0x01 + i, bankSensor("Датчик отработавших газов", "Exhaust gas sensor monitor", i))
        }
        // 0x41..0x50 - подогрев тех же датчиков.
        for (i in 0 until 16) {
            put(0x41 + i, bankSensor("Подогрев датчика отработавших газов", "Exhaust gas sensor heater monitor", i))
        }
        for (b in 1..4) {
            put(0x20 + b, "Катализатор, банк $b" to "Catalyst monitor bank $b")
            put(0x30 + b, "Система EGR, банк $b" to "EGR monitor bank $b")
            put(0x34 + b, "Фазовращатель (VVT), банк $b" to "VVT monitor bank $b")
            put(0x60 + b, "Подогрев катализатора, банк $b" to "Heated catalyst monitor bank $b")
            put(0x70 + b, "Система вторичного воздуха $b" to "Secondary air monitor $b")
            put(0x80 + b, "Топливная система, банк $b" to "Fuel system monitor bank $b")
        }
        // Тесты системы улавливания паров топлива - по размеру искомой утечки
        // в дюймах; это и есть та самая "негерметичность бака".
        put(0x39, "Пары топлива (крышка бака / утечка 0.150\")" to "EVAP monitor (cap off / 0.150\")")
        put(0x3A, "Пары топлива (утечка 0.090\")" to "EVAP monitor (0.090\")")
        put(0x3B, "Пары топлива (утечка 0.040\")" to "EVAP monitor (0.040\")")
        put(0x3C, "Пары топлива (утечка 0.020\")" to "EVAP monitor (0.020\")")
        put(0x3D, "Продувка адсорбера" to "Purge flow monitor")
        put(0xA1, "Пропуски воспламенения, общие данные" to "Misfire monitor general data")
        for (c in 1..16) {
            put(0xA1 + c, "Пропуски воспламенения, цилиндр $c" to "Misfire cylinder $c data")
        }
        put(0xB2, "Сажевый фильтр, банк 1" to "PM filter monitor bank 1")
        put(0xB3, "Сажевый фильтр, банк 2" to "PM filter monitor bank 2")
        for (base in MonitorSupport.RANGE_MIDS) {
            put(base, "Карта поддерживаемых мониторов" to "Supported monitor IDs")
        }
    }

    fun name(mid: Int): String {
        NAMES[mid]?.let { return ObdText.pick(it.first, it.second) }
        return if (mid >= 0xE1) {
            ObdText.pick("Монитор производителя 0x%02X".format(mid), "Manufacturer monitor 0x%02X".format(mid))
        } else {
            ObdText.pick("Монитор 0x%02X".format(mid), "Monitor 0x%02X".format(mid))
        }
    }

    fun isSupportMap(mid: Int): Boolean = mid in MonitorSupport.RANGE_MIDS
}

/** Названия тестов (TID) из SAE J1979 - общие для режимов 05 и 06. */
object TestIds {
    private val NAMES: Map<Int, Pair<String, String>> = mapOf(
        0x01 to ("Порог переключения богатая → бедная" to "Rich to lean threshold voltage"),
        0x02 to ("Порог переключения бедная → богатая" to "Lean to rich threshold voltage"),
        0x03 to ("Нижнее напряжение для замера времени" to "Low voltage for switch time"),
        0x04 to ("Верхнее напряжение для замера времени" to "High voltage for switch time"),
        0x05 to ("Время переключения богатая → бедная" to "Rich to lean switch time"),
        0x06 to ("Время переключения бедная → богатая" to "Lean to rich switch time"),
        0x07 to ("Минимальное напряжение за цикл" to "Minimum voltage for test cycle"),
        0x08 to ("Максимальное напряжение за цикл" to "Maximum voltage for test cycle"),
        0x09 to ("Время между переключениями" to "Time between sensor transitions"),
        0x0A to ("Период датчика" to "Sensor period"),
        0x0B to ("Пропуски воспламенения за 10 циклов (EWMA)" to "EWMA misfire counts for last 10 cycles"),
        0x0C to ("Пропуски воспламенения за текущий цикл" to "Misfire count for current cycle")
    )

    fun name(tid: Int): String {
        NAMES[tid]?.let { return ObdText.pick(it.first, it.second) }
        return if (tid >= 0x80 && tid != 0xFF) {
            ObdText.pick("Тест производителя 0x%02X".format(tid), "Manufacturer test 0x%02X".format(tid))
        } else {
            ObdText.pick("Тест 0x%02X".format(tid), "Test 0x%02X".format(tid))
        }
    }
}
