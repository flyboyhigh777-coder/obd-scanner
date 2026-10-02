package com.obdscanner.core.vehicle

import kotlin.math.PI

/**
 * Типоразмер шины: 245/70 R16 - ширина 245 мм, высота профиля 70% от ширины,
 * посадочный диаметр 16 дюймов.
 *
 * Нужен для двух вещей: оценка мощности (без длины окружности колеса не
 * пересчитать обороты в скорость) и поправка спидометра. Если на машине стоят
 * шины не того размера, который заложен в ЭБУ, то скорость и пробег по шине
 * врут пропорционально отношению длин окружностей - именно это и считает
 * [speedFactorAgainst].
 */
data class TyreSize(val widthMm: Int, val profilePercent: Int, val rimInches: Double) {

    /** Наружный диаметр, мм: посадочный диаметр плюс две высоты профиля. */
    val diameterMm: Double = rimInches * MM_PER_INCH + 2.0 * widthMm * profilePercent / 100.0

    /** Геометрическая длина окружности, м. */
    val circumferenceM: Double = PI * diameterMm / 1000.0

    /**
     * Длина окружности катящегося колеса, м. Под нагрузкой шина проседает, и
     * путь за оборот на 2-4% меньше геометрической длины окружности; берём
     * типовые 3% - это та же поправка, которую применяют производители шин в
     * таблицах "revolutions per mile".
     */
    val rollingCircumferenceM: Double = circumferenceM * ROLLING_FACTOR

    /** Оборотов колеса на километр. */
    val revolutionsPerKm: Double = 1000.0 / rollingCircumferenceM

    /**
     * Во сколько раз настоящая скорость больше показанной, если ЭБУ считает
     * скорость для шины [reference], а стоит эта.
     */
    fun speedFactorAgainst(reference: TyreSize): Double =
        rollingCircumferenceM / reference.rollingCircumferenceM

    override fun toString(): String {
        val rim = if (rimInches % 1.0 == 0.0) rimInches.toInt().toString() else rimInches.toString()
        return "$widthMm/$profilePercent R$rim"
    }

    companion object {
        private const val MM_PER_INCH = 25.4
        private const val ROLLING_FACTOR = 0.97

        /** Разбирает "245/70R16", "245/70 R16", "245/70R16C", "265/60 r18". */
        fun parse(text: String): TyreSize? {
            val match = Regex("""(\d{3})\s*/\s*(\d{2,3})\s*[Rr]\s*(\d{2}(?:\.5)?)""").find(text) ?: return null
            val width = match.groupValues[1].toIntOrNull() ?: return null
            val profile = match.groupValues[2].toIntOrNull() ?: return null
            val rim = match.groupValues[3].toDoubleOrNull() ?: return null
            if (width !in 100..400 || profile !in 20..110 || rim !in 10.0..26.0) return null
            return TyreSize(width, profile, rim)
        }
    }
}
