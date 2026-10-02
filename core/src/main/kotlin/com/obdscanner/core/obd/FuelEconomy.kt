package com.obdscanner.core.obd

/**
 * Расход топлива. Прямой PID 0x5E (engine fuel rate) есть далеко не на всех
 * машинах, поэтому основной путь - пересчёт из расхода воздуха (MAF, PID 0x10):
 * двигатель поддерживает стехиометрическую смесь, значит масса топлива = масса
 * воздуха / AFR, а литры = масса / плотность.
 *
 * Это оценка, а не показания расходомера: на обогащённой смеси (холодный пуск,
 * полная нагрузка) ЭБУ отходит от стехиометрии, и расход в этот момент выше
 * расчётного. Для дизеля стехиометрия тем более условна - там расчёт по MAF
 * систематически занижает, поэтому если машина отдаёт 0x5E, берём его.
 */
object FuelEconomy {

    /** Воздух/топливо по массе и плотность топлива, г/л. */
    private const val GASOLINE_AFR = 14.7
    private const val GASOLINE_DENSITY = 737.0
    private const val DIESEL_AFR = 14.5
    private const val DIESEL_DENSITY = 832.0

    /** Литры в час из расхода воздуха, г/с. */
    fun litresPerHourFromMaf(mafGramsPerSecond: Double, diesel: Boolean = false): Double {
        val afr = if (diesel) DIESEL_AFR else GASOLINE_AFR
        val density = if (diesel) DIESEL_DENSITY else GASOLINE_DENSITY
        return mafGramsPerSecond * 3600.0 / (afr * density)
    }

    /**
     * Литры на 100 км. На стоянке (скорость 0) величина не определена - вместо
     * бесконечности возвращаем null, а приложение показывает в этот момент л/ч.
     */
    fun litresPer100Km(litresPerHour: Double, speedKmh: Double): Double? {
        if (speedKmh < 1.0) return null
        return litresPerHour / speedKmh * 100.0
    }

    /** Километры на литре - для тех, кому привычнее так (и шаг к MPG). */
    fun kmPerLitre(litresPerHour: Double, speedKmh: Double): Double? {
        if (litresPerHour <= 0.0) return null
        return speedKmh / litresPerHour
    }

    /**
     * Запас хода, км: сколько осталось в баке, поделить на расход.
     *
     * Датчик уровня топлива отдаёт проценты, а не литры, поэтому объём бака
     * берётся из профиля машины - и ошибка в нём переносится в запас хода
     * один в один. На бездорожье и при болтанке в баке показания датчика сами
     * пляшут, так что запас хода - ориентир, а не обещание.
     */
    fun rangeKm(tankLitres: Double, levelPercent: Double, litresPer100Km: Double): Double? {
        if (tankLitres <= 0 || levelPercent <= 0 || litresPer100Km <= 0.1) return null
        val litresLeft = tankLitres * levelPercent / 100.0
        return litresLeft / litresPer100Km * 100.0
    }

    /** Мили на галлон (US) из л/100км. */
    fun mpgFromL100(l100: Double): Double? = if (l100 <= 0.0) null else 235.215 / l100
}
