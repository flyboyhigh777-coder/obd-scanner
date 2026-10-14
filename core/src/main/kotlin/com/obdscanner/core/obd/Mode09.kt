package com.obdscanner.core.obd

import com.obdscanner.core.elm.ObdText

/**
 * Режим 09 - данные об автомобиле и о прошивке блока управления.
 *
 * Полезного здесь больше, чем кажется. VIN отвечает на вопрос "та ли это
 * машина", а **CALID** и **CVN** - на вопрос "та ли в ней прошивка": CALID это
 * номер калибровки, который производитель меняет при каждой официальной
 * перепрошивке, а CVN - контрольная сумма этой калибровки. Если CALID
 * заводской, а CVN не совпадает с заводским, прошивку правили - именно так
 * чип-тюнинг и находят на техосмотре в Европе.
 *
 * Блок может сообщить **несколько** CALID и несколько CVN - по одному на каждый
 * программный модуль. Читать только первый (и тем более считать, что он один) -
 * ошибка: у современных ЭБУ их два-три.
 */
object Mode09 {
    const val SERVICE = 0x09

    const val SUPPORTED = 0x00
    const val VIN_MESSAGE_COUNT = 0x01
    const val VIN = 0x02
    const val CALID_MESSAGE_COUNT = 0x03
    const val CALID = 0x04
    const val CVN_MESSAGE_COUNT = 0x05
    const val CVN = 0x06
    const val IPT_MESSAGE_COUNT = 0x07
    const val IPT_SPARK = 0x08
    const val ECU_NAME_MESSAGE_COUNT = 0x09
    const val ECU_NAME = 0x0A
    const val IPT_COMPRESSION = 0x0B

    /** Длина одного CALID в байтах - фиксированная по стандарту. */
    const val CALID_ITEM_BYTES = 16

    /** Длина одного CVN в байтах. */
    const val CVN_ITEM_BYTES = 4

    /**
     * Ответы режима 09 на шине CAN приходят с ведущим байтом - числом элементов
     * данных; на старых шинах его нет.
     *
     * Отличать его по значению ("маленькое число - значит счётчик") нельзя: у
     * счётчика элементов режима 09 PID 08 значение 20, а первый байт
     * счётчика наработки вполне может быть нулём. Зато длина полезной части
     * всегда кратна размеру элемента - 17 байт у VIN, 16 у CALID, 4 у CVN,
     * 2 у счётчиков. Остаток 1 и означает лишний ведущий байт.
     *
     * Без этой проверки VIN на части машин читается со сдвигом на символ, а
     * счётчики наработки - сдвинутыми на целый байт, то есть превращаются в
     * мусор.
     */
    fun dropCountByte(payload: IntArray, itemBytes: Int = 0): IntArray {
        if (payload.isEmpty()) return payload
        if (itemBytes > 0) {
            return if (payload.size % itemBytes == 1) payload.copyOfRange(1, payload.size) else payload
        }
        val first = payload[0]
        return if (first in 1..0x0F) payload.copyOfRange(1, payload.size) else payload
    }

    /** ASCII-строка без нулей и прочего мусора, которым блок добивает поле. */
    fun text(payload: IntArray): String =
        payload.filter { it in 0x20..0x7E }
            .map { it.toChar() }
            .joinToString("")
            .trim()

    /**
     * Разбор нескольких CALID из одного ответа: поля идут подряд по 16 байт,
     * короткое имя добито нулями.
     */
    fun calibrationIds(payload: IntArray): List<String> =
        chunks(dropCountByte(payload, CALID_ITEM_BYTES), CALID_ITEM_BYTES).map { text(it) }.filter { it.isNotEmpty() }

    /**
     * CVN - не текст, а четыре байта контрольной суммы, поэтому показывается
     * шестнадцатеричным числом. Пытаться прочитать их как ASCII - популярная
     * ошибка: на экране получается мусор вроде "?Z#\u0001".
     */
    fun calibrationVerificationNumbers(payload: IntArray): List<String> =
        chunks(dropCountByte(payload, CVN_ITEM_BYTES), CVN_ITEM_BYTES)
            .filter { it.size == CVN_ITEM_BYTES }
            .map { bytes -> bytes.joinToString("") { "%02X".format(it) } }

    private fun chunks(data: IntArray, size: Int): List<IntArray> {
        if (size <= 0) return emptyList()
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < data.size) {
            val to = minOf(i + size, data.size)
            out.add(data.copyOfRange(i, to))
            i = to
        }
        return out
    }
}

/**
 * Счётчик "наработки" бортовых тестов (In-use performance tracking, режим 09
 * PID 08 для бензина и 0B для дизеля).
 *
 * На каждый тест ЭБУ ведёт два числа: сколько раз тест **завершился** (COMP) и
 * сколько раз **складывались условия** для него (COND). Их отношение
 * показывает, работает ли самодиагностика на самом деле.
 *
 * Зачем это нужно живьём: после сброса ошибок готовность (режим 01 PID 01)
 * показывает "тест завершён" уже через один удачный цикл, и машину примут на
 * техосмотре. Эти же счётчики показывают, сколько циклов тест на самом деле
 * проходил, - то есть отличают исправную систему от только что сброшенной.
 */
data class PerformanceCounter(
    val key: String,
    val ru: String,
    val en: String,
    val value: Int
) {
    val title: String get() = ObdText.pick(ru, en)
}

/**
 * Пара счётчиков одного теста: сколько раз он завершился и сколько раз
 * складывались условия для него. Само по себе ни то, ни другое число ни о чём
 * не говорит - смысл в их отношении.
 */
data class PerformancePair(
    val ru: String,
    val en: String,
    val completed: PerformanceCounter,
    val conditions: PerformanceCounter
) {
    val title: String get() = ObdText.pick(ru, en)

    /** Доля завершённых тестов, 0..1; null - условий не было ни разу. */
    val ratio: Double?
        get() = if (conditions.value == 0) null else completed.value.toDouble() / conditions.value.toDouble()
}

data class InUsePerformance(
    val counters: List<PerformanceCounter>,
    val compressionIgnition: Boolean
) {
    fun value(key: String): Int? = counters.firstOrNull { it.key == key }?.value

    /** Сколько циклов вождения блок вообще считал пригодными для диагностики. */
    val obdConditions: Int? get() = value("OBDCOND")

    /** Циклов зажигания с момента, когда счётчики обнулили. */
    val ignitionCycles: Int? get() = value("IGNCNTR")

    /**
     * Отношение "завершено / условия были" для пары счётчиков. Именно это число
     * и нормируется законом (в Европе - не ниже 0,336 для большинства тестов).
     */
    fun ratio(completedKey: String, conditionsKey: String): Double? {
        val completed = value(completedKey) ?: return null
        val conditions = value(conditionsKey) ?: return null
        if (conditions == 0) return null
        return completed.toDouble() / conditions.toDouble()
    }

    /**
     * Пары "завершено / были условия" в порядке стандарта - для таблицы на
     * экране. Название у пары своё, а не взятое у одного из счётчиков: иначе в
     * таблице стояло бы "Катализатор, банк 1: завершено" напротив числа,
     * которое и есть "завершено", - то же слово дважды.
     */
    val pairs: List<PerformancePair>
        get() {
            val byKey = counters.associateBy { it.key }
            return PAIRS.mapNotNull { pair ->
                val completed = byKey[pair.completedKey] ?: return@mapNotNull null
                val conditions = byKey[pair.conditionsKey] ?: return@mapNotNull null
                PerformancePair(pair.ru, pair.en, completed, conditions)
            }
        }

    companion object {
        /** Один счётчик - два байта. */
        const val COUNTER_BYTES = 2

        private class PairName(
            val completedKey: String,
            val conditionsKey: String,
            val ru: String,
            val en: String
        )

        private val PAIRS: List<PairName> = listOf(
            PairName("CATCOMP1", "CATCOND1", "Катализатор, банк 1", "Catalyst bank 1"),
            PairName("CATCOMP2", "CATCOND2", "Катализатор, банк 2", "Catalyst bank 2"),
            PairName("O2SCOMP1", "O2SCOND1", "Лямбда-зонды, банк 1", "Oxygen sensors bank 1"),
            PairName("O2SCOMP2", "O2SCOND2", "Лямбда-зонды, банк 2", "Oxygen sensors bank 2"),
            PairName("EGRCOMP", "EGRCOND", "EGR / фазовращатель", "EGR / VVT"),
            PairName("AIRCOMP", "AIRCOND", "Вторичный воздух", "Secondary air"),
            PairName("EVAPCOMP", "EVAPCOND", "Пары топлива", "EVAP system"),
            PairName("SO2SCOMP1", "SO2SCOND1", "Задний зонд, банк 1", "Secondary O2 bank 1"),
            PairName("SO2SCOMP2", "SO2SCOND2", "Задний зонд, банк 2", "Secondary O2 bank 2"),
            PairName("HCCATCOMP", "HCCATCOND", "Катализатор NMHC", "NMHC catalyst"),
            PairName("NCATCOMP", "NCATCOND", "Катализатор NOx/SCR", "NOx/SCR catalyst"),
            PairName("NADSCOMP", "NADSCOND", "Накопитель NOx", "NOx adsorber"),
            PairName("PMCOMP", "PMCOND", "Сажевый фильтр", "PM filter"),
            PairName("EGSCOMP", "EGSCOND", "Датчики отработавших газов", "Exhaust gas sensors"),
            PairName("BPCOMP", "BPCOND", "Давление наддува", "Boost pressure"),
            PairName("FUELCOMP", "FUELCOND", "Топливная система", "Fuel system")
        )

        /** Порядок счётчиков для бензинового двигателя (PID 08). */
        private val SPARK: List<Triple<String, String, String>> = listOf(
            Triple("OBDCOND", "Циклов, пригодных для диагностики", "OBD monitoring conditions"),
            Triple("IGNCNTR", "Циклов зажигания", "Ignition cycles"),
            Triple("CATCOMP1", "Катализатор, банк 1: завершено", "Catalyst bank 1 completed"),
            Triple("CATCOND1", "Катализатор, банк 1: условия", "Catalyst bank 1 conditions"),
            Triple("CATCOMP2", "Катализатор, банк 2: завершено", "Catalyst bank 2 completed"),
            Triple("CATCOND2", "Катализатор, банк 2: условия", "Catalyst bank 2 conditions"),
            Triple("O2SCOMP1", "Лямбда-зонды, банк 1: завершено", "Oxygen sensor bank 1 completed"),
            Triple("O2SCOND1", "Лямбда-зонды, банк 1: условия", "Oxygen sensor bank 1 conditions"),
            Triple("O2SCOMP2", "Лямбда-зонды, банк 2: завершено", "Oxygen sensor bank 2 completed"),
            Triple("O2SCOND2", "Лямбда-зонды, банк 2: условия", "Oxygen sensor bank 2 conditions"),
            Triple("EGRCOMP", "EGR/VVT: завершено", "EGR/VVT completed"),
            Triple("EGRCOND", "EGR/VVT: условия", "EGR/VVT conditions"),
            Triple("AIRCOMP", "Вторичный воздух: завершено", "Secondary air completed"),
            Triple("AIRCOND", "Вторичный воздух: условия", "Secondary air conditions"),
            Triple("EVAPCOMP", "Пары топлива: завершено", "EVAP completed"),
            Triple("EVAPCOND", "Пары топлива: условия", "EVAP conditions"),
            Triple("SO2SCOMP1", "Задний лямбда-зонд, банк 1: завершено", "Secondary O2 bank 1 completed"),
            Triple("SO2SCOND1", "Задний лямбда-зонд, банк 1: условия", "Secondary O2 bank 1 conditions"),
            Triple("SO2SCOMP2", "Задний лямбда-зонд, банк 2: завершено", "Secondary O2 bank 2 completed"),
            Triple("SO2SCOND2", "Задний лямбда-зонд, банк 2: условия", "Secondary O2 bank 2 conditions")
        )

        /** Порядок счётчиков для дизеля (PID 0B) - набор тестов у него другой. */
        private val COMPRESSION: List<Triple<String, String, String>> = listOf(
            Triple("OBDCOND", "Циклов, пригодных для диагностики", "OBD monitoring conditions"),
            Triple("IGNCNTR", "Циклов зажигания", "Ignition cycles"),
            Triple("HCCATCOMP", "Катализатор NMHC: завершено", "NMHC catalyst completed"),
            Triple("HCCATCOND", "Катализатор NMHC: условия", "NMHC catalyst conditions"),
            Triple("NCATCOMP", "Катализатор NOx/SCR: завершено", "NOx/SCR catalyst completed"),
            Triple("NCATCOND", "Катализатор NOx/SCR: условия", "NOx/SCR catalyst conditions"),
            Triple("NADSCOMP", "Накопитель NOx: завершено", "NOx adsorber completed"),
            Triple("NADSCOND", "Накопитель NOx: условия", "NOx adsorber conditions"),
            Triple("PMCOMP", "Сажевый фильтр: завершено", "PM filter completed"),
            Triple("PMCOND", "Сажевый фильтр: условия", "PM filter conditions"),
            Triple("EGSCOMP", "Датчики отработавших газов: завершено", "Exhaust gas sensor completed"),
            Triple("EGSCOND", "Датчики отработавших газов: условия", "Exhaust gas sensor conditions"),
            Triple("BPCOMP", "Давление наддува: завершено", "Boost pressure completed"),
            Triple("BPCOND", "Давление наддува: условия", "Boost pressure conditions"),
            Triple("FUELCOMP", "Топливная система: завершено", "Fuel system completed"),
            Triple("FUELCOND", "Топливная система: условия", "Fuel system conditions")
        )

        /**
         * Счётчики из ответа. Каждый - два байта, порядок задан стандартом;
         * лишние пары (их добавляли в поздних редакциях - сажевый фильтр у
         * бензина, неравномерность смеси по цилиндрам) показываются номером,
         * а не выдуманным названием.
         */
        fun decode(payload: IntArray, compressionIgnition: Boolean): InUsePerformance {
            val data = Mode09.dropCountByte(payload, COUNTER_BYTES)
            val names = if (compressionIgnition) COMPRESSION else SPARK
            val out = ArrayList<PerformanceCounter>()
            var i = 0
            var index = 0
            while (i + 1 < data.size) {
                val value = (data[i] shl 8) or data[i + 1]
                val named = names.getOrNull(index)
                out.add(
                    if (named != null) {
                        PerformanceCounter(named.first, named.second, named.third, value)
                    } else {
                        val n = index + 1
                        PerformanceCounter("ITEM$n", "Счётчик $n", "Counter $n", value)
                    }
                )
                i += 2
                index++
            }
            return InUsePerformance(out, compressionIgnition)
        }
    }
}
