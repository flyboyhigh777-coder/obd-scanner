package com.obdscanner.app.ui

import com.obdscanner.core.elm.ObdText
import com.obdscanner.core.obd.ObdPid
import com.obdscanner.core.obd.ObdUnit
import com.obdscanner.core.obd.UnitConversion
import com.obdscanner.core.obd.UnitSystem

/**
 * Приведение чисел к виду, пригодному для экрана. Форматируется по локали
 * устройства (в русской - запятая как десятичный разделитель и пробел между
 * тысячами), в отличие от выгрузки CSV, где разделитель всегда точка.
 */
object Format {

    /** Значение без единицы: "1 726", "88,5". */
    fun number(value: Double, decimals: Int): String =
        if (decimals <= 0) "%,.0f".format(value) else "%,.${decimals}f".format(value)

    /** Значение с единицей в выбранной системе. */
    fun value(value: Double, unit: ObdUnit, decimals: Int, system: UnitSystem): String {
        val converted = UnitConversion.convert(value, unit, system, decimals)
        val text = number(converted.value, converted.decimals)
        return if (converted.unit.isEmpty()) text else "$text ${converted.unit}"
    }

    fun value(pid: ObdPid, raw: Double, system: UnitSystem): String =
        value(raw, pid.unit, pid.decimals, system)

    /** Только число, без единицы - для больших цифр на шкале. */
    fun number(pid: ObdPid, raw: Double, system: UnitSystem): String {
        val converted = UnitConversion.convert(raw, pid.unit, system, pid.decimals)
        return number(converted.value, converted.decimals)
    }

    /** Только подпись единицы в выбранной системе. */
    fun unit(pid: ObdPid, system: UnitSystem): String =
        UnitConversion.convert(0.0, pid.unit, system, pid.decimals).unit

    /** Границы шкалы прибора, пересчитанные в ту же систему. */
    fun range(pid: ObdPid, system: UnitSystem): ClosedFloatingPointRange<Double> {
        val min = UnitConversion.convert(pid.min, pid.unit, system, pid.decimals).value
        val max = UnitConversion.convert(pid.max, pid.unit, system, pid.decimals).value
        return if (min <= max) min..max else max..min
    }

    /** "1 ч 12 мин" / "4 мин 05 с" - читается быстрее, чем 01:12:00. */
    fun duration(millis: Long): String {
        val hourSuffix = ObdText.pick("ч", "h")
        val minuteSuffix = ObdText.pick("мин", "min")
        val secondSuffix = ObdText.pick("с", "s")
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "$hours $hourSuffix ${"%02d".format(minutes)} $minuteSuffix"
            minutes > 0 -> "$minutes $minuteSuffix ${"%02d".format(seconds)} $secondSuffix"
            else -> "$seconds $secondSuffix"
        }
    }
}
