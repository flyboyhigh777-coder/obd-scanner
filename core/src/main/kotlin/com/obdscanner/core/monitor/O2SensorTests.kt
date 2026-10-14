package com.obdscanner.core.monitor

import com.obdscanner.core.elm.ObdText

/**
 * Где в выпускном тракте стоят лямбда-зонды.
 *
 * Шина отдаёт это двумя разными PID, и машина поддерживает ровно один из них:
 *  - **0x13** - два банка по четыре датчика: биты 0-3 это банк 1, датчики 1-4,
 *    биты 4-7 - банк 2;
 *  - **0x1D** - четыре банка по два датчика: биты 0-1 - банк 1, 2-3 - банк 2,
 *    4-5 - банк 3, 6-7 - банк 4.
 *
 * Разница не косметическая: по одному и тому же байту 0x11 первая раскладка
 * даёт "банк 1 датчики 1 и 2", а вторая - "банк 1 датчик 1 и банк 3 датчик 1".
 * Какая из них верна, видно по тому, какой PID машина объявила поддерживаемым.
 *
 * [mask] - то, чем этот датчик обозначается в запросе режима 05. Там номер
 * датчика передаётся не по порядку (1, 2, 3...), а битом: 0x01 - первый датчик
 * первого банка, 0x02 - второй, 0x10 - первый датчик второго банка. Перепутать
 * легко, и тогда ЭБУ отвечает про другой датчик, не сообщая об ошибке.
 */
data class O2SensorLocation(
    val bank: Int,
    val sensor: Int,
    val mask: Int,
    val present: Boolean
) {
    val title: String
        get() = ObdText.pick("Банк $bank, датчик $sensor", "Bank $bank sensor $sensor")

    companion object {
        /** Раскладка PID 0x13: 2 банка по 4 датчика. */
        fun decodeTwoBanks(byte: Int): List<O2SensorLocation> =
            (0 until 8).map { bit ->
                O2SensorLocation(
                    bank = bit / 4 + 1,
                    sensor = bit % 4 + 1,
                    mask = 1 shl bit,
                    present = (byte shr bit) and 1 == 1
                )
            }

        /** Раскладка PID 0x1D: 4 банка по 2 датчика. */
        fun decodeFourBanks(byte: Int): List<O2SensorLocation> =
            (0 until 8).map { bit ->
                O2SensorLocation(
                    bank = bit / 2 + 1,
                    sensor = bit % 2 + 1,
                    mask = 1 shl bit,
                    present = (byte shr bit) and 1 == 1
                )
            }
    }
}

/**
 * Режим 05 - результаты тестов лямбда-зондов на старых шинах (J1850, ISO 9141-2,
 * KWP2000).
 *
 * На машинах с CAN этого режима нет: там то же самое отдаёт режим 06, и
 * стандарт прямо запрещает дублировать. Поэтому режим 05 - не "устаревший", а
 * единственный способ увидеть времена переключения зонда на машине до 2008 года.
 *
 * Масштаб здесь не передаётся вообще - он задан стандартом для каждого номера
 * теста, и промахнуться нельзя: для времён это 0,004 с на отсчёт, для
 * напряжений 0,005 В, а для периода датчика - 0,04 с.
 */
data class O2SensorTest(
    val tid: Int,
    /** Бит датчика из запроса (0x01, 0x02, 0x04, ... 0x80). */
    val sensorMask: Int,
    val raw: Int
) {
    val kind: Kind get() = kindOf(tid)

    enum class Kind { VOLTAGE, TIME, PERIOD, UNKNOWN }

    val value: Double?
        get() = when (kind) {
            Kind.VOLTAGE -> raw * 0.005
            Kind.TIME -> raw * 0.004
            Kind.PERIOD -> raw * 0.04
            Kind.UNKNOWN -> null
        }

    val unit: String
        get() = when (kind) {
            Kind.VOLTAGE -> ObdText.pick("В", "V")
            Kind.TIME, Kind.PERIOD -> ObdText.pick("с", "s")
            Kind.UNKNOWN -> ""
        }

    val max: Double?
        get() = when (kind) {
            Kind.VOLTAGE -> 1.275
            Kind.TIME -> 1.02
            Kind.PERIOD -> 10.2
            Kind.UNKNOWN -> null
        }

    val title: String get() = TestIds.name(tid)

    /** Название датчика по биту из запроса, в раскладке "2 банка по 4". */
    val sensorTitle: String
        get() {
            val bit = Integer.numberOfTrailingZeros(sensorMask)
            if (sensorMask == 0 || bit > 7) return ObdText.pick("датчик ?", "sensor ?")
            return ObdText.pick("банк ${bit / 4 + 1}, датчик ${bit % 4 + 1}", "bank ${bit / 4 + 1} sensor ${bit % 4 + 1}")
        }

    fun format(): String {
        val v = value ?: return ObdText.pick("$raw отсч.", "$raw counts")
        val decimals = if (kind == Kind.VOLTAGE) 3 else 3
        return "%.${decimals}f %s".format(v, unit)
    }

    companion object {
        /** Номера тестов, которые стандарт описывает для режима 05. */
        val TIDS: List<Int> = listOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A)

        /** Биты датчиков в том порядке, в каком их принято перебирать. */
        val SENSOR_MASKS: List<Int> = listOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80)

        fun kindOf(tid: Int): Kind = when (tid) {
            0x01, 0x02, 0x03, 0x04, 0x07, 0x08 -> Kind.VOLTAGE
            0x05, 0x06, 0x09 -> Kind.TIME
            0x0A -> Kind.PERIOD
            else -> Kind.UNKNOWN
        }

        /**
         * Ответ режима 05: `45 TID датчик значение`. [payload] - байты после
         * 0x45.
         */
        fun decode(payload: IntArray): O2SensorTest? {
            if (payload.size < 3) return null
            return O2SensorTest(tid = payload[0], sensorMask = payload[1], raw = payload[2])
        }
    }
}
