package com.obdscanner.core.obd

import com.obdscanner.core.elm.ObdText

/** Единица измерения значения PID - и для подписи, и для пересчёта в имперскую систему. */
enum class ObdUnit(val ru: String, val en: String) {
    NONE("", ""),
    RPM("об/мин", "rpm"),
    KMH("км/ч", "km/h"),
    CELSIUS("°C", "°C"),
    PERCENT("%", "%"),
    KPA("кПа", "kPa"),
    VOLT("В", "V"),
    GRAM_PER_SEC("г/с", "g/s"),
    LITRE_PER_HOUR("л/ч", "L/h"),
    LITRE_PER_100KM("л/100км", "L/100km"),
    SECOND("с", "s"),
    MINUTE("мин", "min"),
    KM("км", "km"),
    DEGREE("°", "°"),
    NEWTON_METRE("Н·м", "N·m"),
    RATIO("", "");

    val label: String get() = ObdText.pick(ru, en)
}

/**
 * Описание одного PID режима 01/02 по SAE J1979: сколько байт данных приходит в
 * ответе, как их пересчитать в физическую величину и в каких пределах эта
 * величина бывает (пределы нужны шкалам приборов на экране).
 *
 * [decode] возвращает null, если данных пришло меньше, чем нужно - такое бывает
 * на дешёвых адаптерах и на машинах, которые отвечают обрезанным кадром.
 */
data class ObdPid(
    val id: Int,
    val dataBytes: Int,
    val ru: String,
    val en: String,
    val unit: ObdUnit,
    val min: Double = 0.0,
    val max: Double = 100.0,
    /** Сколько знаков после запятой показывать. */
    val decimals: Int = 0,
    val decode: (IntArray) -> Double?
) {
    val title: String get() = ObdText.pick(ru, en)

    /** Запрос этого PID: "010C". */
    val request: String get() = "01%02X".format(id)

    fun format(value: Double): String = "%.${decimals}f".format(value)
}

private fun pid(
    id: Int,
    dataBytes: Int,
    ru: String,
    en: String,
    unit: ObdUnit,
    min: Double = 0.0,
    max: Double = 100.0,
    decimals: Int = 0,
    decode: (IntArray) -> Double?
) = ObdPid(id, dataBytes, ru, en, unit, min, max, decimals, decode)

/** Однобайтный декодер: нужен хотя бы один байт. */
private inline fun one(crossinline f: (Int) -> Double): (IntArray) -> Double? =
    { d -> if (d.isEmpty()) null else f(d[0]) }

/** Двухбайтный декодер: нужны оба байта (A и B). */
private inline fun two(crossinline f: (Int, Int) -> Double): (IntArray) -> Double? =
    { d -> if (d.size < 2) null else f(d[0], d[1]) }

/**
 * Каталог стандартных PID режима 01. Формулы - из SAE J1979 (таблица
 * "Standard PIDs"), те же, что приводит и общедоступная сводка Wikipedia
 * "OBD-II PIDs"; каждая закрыта тестом с реальным ответом адаптера.
 *
 * Выборка не полная (в стандарте PID до 0xC4 плюс режимы 06/08), а та, которую
 * отдаёт большинство машин и которую имеет смысл показывать водителю.
 */
object Pids {

    val MONITOR_STATUS = pid(0x01, 4, "Статус бортовых тестов", "Monitor status", ObdUnit.NONE, 0.0, 255.0,
        decode = one { (it and 0x7F).toDouble() }) // число ошибок; разбор целиком - в MonitorStatus

    val FUEL_SYSTEM_STATUS = pid(0x03, 2, "Статус топливной системы", "Fuel system status", ObdUnit.NONE, 0.0, 255.0,
        decode = one { it.toDouble() })

    val ENGINE_LOAD = pid(0x04, 1, "Нагрузка на двигатель", "Calculated engine load", ObdUnit.PERCENT, 0.0, 100.0,
        decode = one { it * 100.0 / 255.0 })

    val COOLANT_TEMP = pid(0x05, 1, "Температура ОЖ", "Coolant temperature", ObdUnit.CELSIUS, -40.0, 130.0,
        decode = one { it - 40.0 })

    val SHORT_FUEL_TRIM_1 = pid(0x06, 1, "Коррекция топлива, краткая (банк 1)", "Short term fuel trim (bank 1)",
        ObdUnit.PERCENT, -100.0, 99.2, decimals = 1, decode = one { it / 1.28 - 100.0 })

    val LONG_FUEL_TRIM_1 = pid(0x07, 1, "Коррекция топлива, долгая (банк 1)", "Long term fuel trim (bank 1)",
        ObdUnit.PERCENT, -100.0, 99.2, decimals = 1, decode = one { it / 1.28 - 100.0 })

    val SHORT_FUEL_TRIM_2 = pid(0x08, 1, "Коррекция топлива, краткая (банк 2)", "Short term fuel trim (bank 2)",
        ObdUnit.PERCENT, -100.0, 99.2, decimals = 1, decode = one { it / 1.28 - 100.0 })

    val LONG_FUEL_TRIM_2 = pid(0x09, 1, "Коррекция топлива, долгая (банк 2)", "Long term fuel trim (bank 2)",
        ObdUnit.PERCENT, -100.0, 99.2, decimals = 1, decode = one { it / 1.28 - 100.0 })

    val FUEL_PRESSURE = pid(0x0A, 1, "Давление топлива", "Fuel pressure", ObdUnit.KPA, 0.0, 765.0,
        decode = one { it * 3.0 })

    val INTAKE_MAP = pid(0x0B, 1, "Давление во впуске (MAP)", "Intake manifold pressure", ObdUnit.KPA, 0.0, 255.0,
        decode = one { it.toDouble() })

    val RPM = pid(0x0C, 2, "Обороты двигателя", "Engine RPM", ObdUnit.RPM, 0.0, 8000.0,
        decode = two { a, b -> (a * 256 + b) / 4.0 })

    val SPEED = pid(0x0D, 1, "Скорость", "Vehicle speed", ObdUnit.KMH, 0.0, 255.0,
        decode = one { it.toDouble() })

    val TIMING_ADVANCE = pid(0x0E, 1, "Угол опережения зажигания", "Timing advance", ObdUnit.DEGREE, -64.0, 63.5,
        decimals = 1, decode = one { it / 2.0 - 64.0 })

    val INTAKE_TEMP = pid(0x0F, 1, "Температура воздуха на впуске", "Intake air temperature", ObdUnit.CELSIUS,
        -40.0, 100.0, decode = one { it - 40.0 })

    val MAF = pid(0x10, 2, "Расход воздуха (MAF)", "Mass air flow", ObdUnit.GRAM_PER_SEC, 0.0, 300.0,
        decimals = 2, decode = two { a, b -> (a * 256 + b) / 100.0 })

    val THROTTLE = pid(0x11, 1, "Положение дросселя", "Throttle position", ObdUnit.PERCENT, 0.0, 100.0,
        decode = one { it * 100.0 / 255.0 })

    val O2_B1S1_VOLTAGE = pid(0x14, 2, "Лямбда-зонд 1 (банк 1), напряжение", "O2 sensor 1 (bank 1) voltage",
        ObdUnit.VOLT, 0.0, 1.275, decimals = 3, decode = two { a, _ -> a / 200.0 })

    val O2_B1S2_VOLTAGE = pid(0x15, 2, "Лямбда-зонд 2 (банк 1), напряжение", "O2 sensor 2 (bank 1) voltage",
        ObdUnit.VOLT, 0.0, 1.275, decimals = 3, decode = two { a, _ -> a / 200.0 })

    val OBD_STANDARD = pid(0x1C, 1, "Стандарт OBD", "OBD standard", ObdUnit.NONE, 0.0, 255.0,
        decode = one { it.toDouble() })

    val RUN_TIME = pid(0x1F, 2, "Время работы двигателя", "Run time since start", ObdUnit.SECOND, 0.0, 65535.0,
        decode = two { a, b -> (a * 256 + b).toDouble() })

    val DISTANCE_WITH_MIL = pid(0x21, 2, "Пробег с горящим Check Engine", "Distance with MIL on", ObdUnit.KM,
        0.0, 65535.0, decode = two { a, b -> (a * 256 + b).toDouble() })

    val FUEL_RAIL_PRESSURE = pid(0x23, 2, "Давление в топливной рейке", "Fuel rail gauge pressure", ObdUnit.KPA,
        0.0, 655350.0, decode = two { a, b -> (a * 256 + b) * 10.0 })

    val COMMANDED_EGR = pid(0x2C, 1, "Заданное положение EGR", "Commanded EGR", ObdUnit.PERCENT, 0.0, 100.0,
        decode = one { it * 100.0 / 255.0 })

    val EGR_ERROR = pid(0x2D, 1, "Ошибка EGR", "EGR error", ObdUnit.PERCENT, -100.0, 99.2, decimals = 1,
        decode = one { it / 1.28 - 100.0 })

    val FUEL_LEVEL = pid(0x2F, 1, "Уровень топлива в баке", "Fuel tank level", ObdUnit.PERCENT, 0.0, 100.0,
        decode = one { it * 100.0 / 255.0 })

    val WARMUPS_SINCE_CLEAR = pid(0x30, 1, "Прогревов после сброса ошибок", "Warm-ups since codes cleared",
        ObdUnit.NONE, 0.0, 255.0, decode = one { it.toDouble() })

    val DISTANCE_SINCE_CLEAR = pid(0x31, 2, "Пробег после сброса ошибок", "Distance since codes cleared",
        ObdUnit.KM, 0.0, 65535.0, decode = two { a, b -> (a * 256 + b).toDouble() })

    val BAROMETRIC_PRESSURE = pid(0x33, 1, "Атмосферное давление", "Barometric pressure", ObdUnit.KPA, 0.0, 255.0,
        decode = one { it.toDouble() })

    val CATALYST_TEMP_B1S1 = pid(0x3C, 2, "Температура катализатора (банк 1)", "Catalyst temperature (bank 1)",
        ObdUnit.CELSIUS, -40.0, 6513.5, decimals = 1, decode = two { a, b -> (a * 256 + b) / 10.0 - 40.0 })

    val CONTROL_MODULE_VOLTAGE = pid(0x42, 2, "Напряжение бортовой сети", "Control module voltage", ObdUnit.VOLT,
        0.0, 18.0, decimals = 2, decode = two { a, b -> (a * 256 + b) / 1000.0 })

    val ABSOLUTE_LOAD = pid(0x43, 2, "Абсолютная нагрузка", "Absolute load value", ObdUnit.PERCENT, 0.0, 300.0,
        decimals = 1, decode = two { a, b -> (a * 256 + b) * 100.0 / 255.0 })

    val EQUIVALENCE_RATIO = pid(0x44, 2, "Лямбда (заданная смесь)", "Commanded equivalence ratio", ObdUnit.RATIO,
        0.0, 2.0, decimals = 3, decode = two { a, b -> (a * 256 + b) / 32768.0 })

    val RELATIVE_THROTTLE = pid(0x45, 1, "Положение дросселя (относительное)", "Relative throttle position",
        ObdUnit.PERCENT, 0.0, 100.0, decode = one { it * 100.0 / 255.0 })

    val AMBIENT_TEMP = pid(0x46, 1, "Температура за бортом", "Ambient air temperature", ObdUnit.CELSIUS,
        -40.0, 60.0, decode = one { it - 40.0 })

    val ACCEL_PEDAL_D = pid(0x49, 1, "Педаль акселератора D", "Accelerator pedal position D", ObdUnit.PERCENT,
        0.0, 100.0, decode = one { it * 100.0 / 255.0 })

    val COMMANDED_THROTTLE = pid(0x4C, 1, "Заданное положение дросселя", "Commanded throttle actuator",
        ObdUnit.PERCENT, 0.0, 100.0, decode = one { it * 100.0 / 255.0 })

    val TIME_WITH_MIL = pid(0x4D, 2, "Время с горящим Check Engine", "Time run with MIL on", ObdUnit.MINUTE,
        0.0, 65535.0, decode = two { a, b -> (a * 256 + b).toDouble() })

    val TIME_SINCE_CLEAR = pid(0x4E, 2, "Время после сброса ошибок", "Time since codes cleared", ObdUnit.MINUTE,
        0.0, 65535.0, decode = two { a, b -> (a * 256 + b).toDouble() })

    val FUEL_TYPE = pid(0x51, 1, "Тип топлива", "Fuel type", ObdUnit.NONE, 0.0, 255.0,
        decode = one { it.toDouble() })

    val ETHANOL_PERCENT = pid(0x52, 1, "Доля этанола в топливе", "Ethanol fuel percentage", ObdUnit.PERCENT,
        0.0, 100.0, decimals = 1, decode = one { it * 100.0 / 255.0 })

    val RELATIVE_ACCEL_PEDAL = pid(0x5A, 1, "Педаль акселератора (относительно)", "Relative accelerator pedal",
        ObdUnit.PERCENT, 0.0, 100.0, decode = one { it * 100.0 / 255.0 })

    val HYBRID_BATTERY_LIFE = pid(0x5B, 1, "Остаточный ресурс батареи (гибрид)", "Hybrid battery pack life",
        ObdUnit.PERCENT, 0.0, 100.0, decimals = 1, decode = one { it * 100.0 / 255.0 })

    val OIL_TEMP = pid(0x5C, 1, "Температура масла", "Engine oil temperature", ObdUnit.CELSIUS, -40.0, 210.0,
        decode = one { it - 40.0 })

    val INJECTION_TIMING = pid(0x5D, 2, "Момент впрыска", "Fuel injection timing", ObdUnit.DEGREE, -210.0, 302.0,
        decimals = 2, decode = two { a, b -> (a * 256 + b) / 128.0 - 210.0 })

    val FUEL_RATE = pid(0x5E, 2, "Расход топлива (мгновенный)", "Engine fuel rate", ObdUnit.LITRE_PER_HOUR,
        0.0, 60.0, decimals = 2, decode = two { a, b -> (a * 256 + b) / 20.0 })

    val DEMANDED_TORQUE = pid(0x61, 1, "Запрошенный крутящий момент", "Driver's demanded torque", ObdUnit.PERCENT,
        -125.0, 130.0, decode = one { it - 125.0 })

    val ACTUAL_TORQUE = pid(0x62, 1, "Фактический крутящий момент", "Actual engine torque", ObdUnit.PERCENT,
        -125.0, 130.0, decode = one { it - 125.0 })

    val REFERENCE_TORQUE = pid(0x63, 2, "Номинальный момент двигателя", "Engine reference torque",
        ObdUnit.NEWTON_METRE, 0.0, 65535.0, decode = two { a, b -> (a * 256 + b).toDouble() })

    /** Все известные PID режима 01 по возрастанию номера. */
    val ALL: List<ObdPid> = listOf(
        MONITOR_STATUS, FUEL_SYSTEM_STATUS, ENGINE_LOAD, COOLANT_TEMP,
        SHORT_FUEL_TRIM_1, LONG_FUEL_TRIM_1, SHORT_FUEL_TRIM_2, LONG_FUEL_TRIM_2,
        FUEL_PRESSURE, INTAKE_MAP, RPM, SPEED, TIMING_ADVANCE, INTAKE_TEMP, MAF, THROTTLE,
        O2_B1S1_VOLTAGE, O2_B1S2_VOLTAGE, OBD_STANDARD, RUN_TIME, DISTANCE_WITH_MIL,
        FUEL_RAIL_PRESSURE, COMMANDED_EGR, EGR_ERROR, FUEL_LEVEL, WARMUPS_SINCE_CLEAR,
        DISTANCE_SINCE_CLEAR, BAROMETRIC_PRESSURE, CATALYST_TEMP_B1S1, CONTROL_MODULE_VOLTAGE,
        ABSOLUTE_LOAD, EQUIVALENCE_RATIO, RELATIVE_THROTTLE, AMBIENT_TEMP, ACCEL_PEDAL_D,
        COMMANDED_THROTTLE, TIME_WITH_MIL, TIME_SINCE_CLEAR, FUEL_TYPE, ETHANOL_PERCENT,
        RELATIVE_ACCEL_PEDAL, HYBRID_BATTERY_LIFE, OIL_TEMP, INJECTION_TIMING, FUEL_RATE,
        DEMANDED_TORQUE, ACTUAL_TORQUE, REFERENCE_TORQUE
    ).sortedBy { it.id }

    val byId: Map<Int, ObdPid> = ALL.associateBy { it.id }

    /**
     * Что показывать на приборке по умолчанию, если машина это поддерживает -
     * порядок важен, именно в нём плитки встают на экране.
     */
    val DEFAULT_DASHBOARD: List<Int> = listOf(
        RPM.id, SPEED.id, COOLANT_TEMP.id, ENGINE_LOAD.id, THROTTLE.id,
        INTAKE_TEMP.id, CONTROL_MODULE_VOLTAGE.id, FUEL_LEVEL.id
    )

    /** PID, которые не меняются на ходу - их достаточно прочитать один раз. */
    val STATIC: Set<Int> = setOf(
        OBD_STANDARD.id, FUEL_TYPE.id, REFERENCE_TORQUE.id, DISTANCE_SINCE_CLEAR.id,
        WARMUPS_SINCE_CLEAR.id, TIME_SINCE_CLEAR.id, DISTANCE_WITH_MIL.id, TIME_WITH_MIL.id
    )
}

/** Тип топлива из PID 0x51. */
object FuelType {
    private val NAMES: Map<Int, Pair<String, String>> = mapOf(
        0 to ("Не доступно" to "Not available"),
        1 to ("Бензин" to "Gasoline"),
        2 to ("Метанол" to "Methanol"),
        3 to ("Этанол" to "Ethanol"),
        4 to ("Дизель" to "Diesel"),
        5 to ("Сжиженный газ (LPG)" to "LPG"),
        6 to ("Сжатый природный газ (CNG)" to "CNG"),
        7 to ("Пропан" to "Propane"),
        8 to ("Электро" to "Electric"),
        9 to ("Бензин + газ (бифьюэл)" to "Bifuel gasoline"),
        10 to ("Метанол + газ" to "Bifuel methanol"),
        11 to ("Этанол + газ" to "Bifuel ethanol"),
        12 to ("LPG + газ" to "Bifuel LPG"),
        13 to ("CNG + газ" to "Bifuel CNG"),
        14 to ("Пропан + газ" to "Bifuel propane"),
        15 to ("Электро + ДВС" to "Bifuel electric"),
        17 to ("Гибрид (бензин)" to "Hybrid gasoline"),
        18 to ("Гибрид (этанол)" to "Hybrid ethanol"),
        19 to ("Гибрид (дизель)" to "Hybrid diesel"),
        20 to ("Гибрид (электро)" to "Hybrid electric"),
        23 to ("Гибрид (регенеративный)" to "Hybrid regenerative")
    )

    fun name(code: Int): String {
        val pair = NAMES[code] ?: return ObdText.pick("Код $code", "Code $code")
        return ObdText.pick(pair.first, pair.second)
    }

    /** Дизель расходует топливо иначе - нужно для пересчёта MAF в литры. */
    fun isDiesel(code: Int): Boolean = code == 4 || code == 19
}

/** Стандарт OBD из PID 0x1C - полезен, чтобы понять, чего вообще ждать от машины. */
object ObdStandard {
    private val NAMES: Map<Int, Pair<String, String>> = mapOf(
        1 to ("OBD-II (Калифорния, CARB)" to "OBD-II (CARB)"),
        2 to ("OBD (федеральный, EPA)" to "OBD (EPA)"),
        3 to ("OBD и OBD-II" to "OBD and OBD-II"),
        4 to ("OBD-I" to "OBD-I"),
        5 to ("Без бортовой диагностики" to "Not OBD compliant"),
        6 to ("EOBD (Европа)" to "EOBD"),
        7 to ("EOBD и OBD-II" to "EOBD and OBD-II"),
        8 to ("EOBD и OBD" to "EOBD and OBD"),
        9 to ("EOBD, OBD и OBD-II" to "EOBD, OBD and OBD-II"),
        10 to ("JOBD (Япония)" to "JOBD"),
        11 to ("JOBD и OBD-II" to "JOBD and OBD-II"),
        12 to ("JOBD и EOBD" to "JOBD and EOBD"),
        13 to ("JOBD, EOBD и OBD-II" to "JOBD, EOBD and OBD-II"),
        17 to ("EMD (двигатели)" to "EMD"),
        18 to ("EMD+" to "EMD+"),
        19 to ("HD OBD-C (грузовые)" to "HD OBD-C"),
        20 to ("HD OBD (грузовые)" to "HD OBD"),
        21 to ("WWH OBD" to "WWH OBD"),
        23 to ("HD EOBD-I" to "HD EOBD-I"),
        28 to ("OBDBr-1 (Бразилия)" to "OBDBr-1"),
        29 to ("OBDBr-2 (Бразилия)" to "OBDBr-2")
    )

    fun name(code: Int): String {
        val pair = NAMES[code] ?: return ObdText.pick("Код $code", "Code $code")
        return ObdText.pick(pair.first, pair.second)
    }
}
