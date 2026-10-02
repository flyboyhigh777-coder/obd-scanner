package com.obdscanner.core.obd

import com.obdscanner.core.elm.ObdText

enum class UnitSystem(val ru: String, val en: String) {
    METRIC("Метрические", "Metric"),
    IMPERIAL("Имперские", "Imperial");

    val title: String get() = ObdText.pick(ru, en)
}

/** Значение, готовое к показу: число, подпись единицы и сколько знаков после запятой. */
data class DisplayValue(val value: Double, val unit: String, val decimals: Int)

/**
 * Перевод в имперские единицы. Шина OBD-II всегда отдаёт метрику (это часть
 * стандарта), поэтому пересчёт - дело только показа.
 *
 * Расход - единственный случай, где величина не домножается, а обращается:
 * литры на 100 км и мили на галлон растут в разные стороны, поэтому при
 * пересчёте шкала прибора разворачивается.
 */
object UnitConversion {

    private const val KM_TO_MILE = 0.621371
    private const val KPA_TO_PSI = 0.145038
    private const val G_PER_S_TO_LB_PER_MIN = 0.132277
    private const val L_TO_GAL = 0.264172
    private const val L100KM_TO_MPG = 235.215

    fun convert(value: Double, unit: ObdUnit, system: UnitSystem, decimals: Int = 0): DisplayValue {
        if (system == UnitSystem.METRIC) return DisplayValue(value, unit.label, decimals)
        return when (unit) {
            ObdUnit.KMH -> DisplayValue(value * KM_TO_MILE, "mph", decimals)
            ObdUnit.KM -> DisplayValue(value * KM_TO_MILE, ObdText.pick("миль", "mi"), decimals)
            ObdUnit.CELSIUS -> DisplayValue(value * 1.8 + 32.0, "°F", decimals)
            ObdUnit.KPA -> DisplayValue(value * KPA_TO_PSI, "psi", maxOf(decimals, 1))
            ObdUnit.GRAM_PER_SEC -> DisplayValue(value * G_PER_S_TO_LB_PER_MIN, "lb/min", maxOf(decimals, 2))
            ObdUnit.LITRE_PER_HOUR -> DisplayValue(value * L_TO_GAL, "gal/h", maxOf(decimals, 2))
            // 0 л/100км - это бесконечный MPG; показывать "бесконечность" нельзя,
            // поэтому отдаём ноль, а экран на нулевом расходе и так пишет прочерк.
            ObdUnit.LITRE_PER_100KM -> DisplayValue(
                if (value > 0.01) L100KM_TO_MPG / value else 0.0,
                "mpg",
                maxOf(decimals, 1)
            )
            else -> DisplayValue(value, unit.label, decimals)
        }
    }

    /** Объём в литрах или галлонах - для итогов поездки. */
    fun litres(value: Double, system: UnitSystem): DisplayValue =
        if (system == UnitSystem.METRIC) {
            DisplayValue(value, ObdText.pick("л", "L"), 2)
        } else {
            DisplayValue(value * L_TO_GAL, "gal", 2)
        }

    /** true, если при пересчёте шкала прибора переворачивается (больше = лучше). */
    fun isInverted(unit: ObdUnit, system: UnitSystem): Boolean =
        system == UnitSystem.IMPERIAL && unit == ObdUnit.LITRE_PER_100KM
}
