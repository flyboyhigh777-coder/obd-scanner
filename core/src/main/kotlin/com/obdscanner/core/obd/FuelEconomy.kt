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
     * Расход воздуха, г/с, посчитанный из давления во впуске.
     *
     * Нужно там, где расходомера нет вовсе. ЗМЗ-409 на УАЗ Патриоте - как раз
     * такой случай: воздух не меряется, ЭБУ считает его сам по давлению во
     * впуске, оборотам и температуре (так называемый speed-density), а наружу
     * ни PID 0x10 (MAF), ни PID 0x5E (расход топлива) не отдаёт. Без этого
     * расчёта расход на такой машине не показать вообще никак.
     *
     * Физика простая. За два оборота четырёхтактный двигатель прокачивает
     * через себя один рабочий объём, но не полностью - часть объёма теряется
     * на сопротивлении впуска, и эту долю называют коэффициентом наполнения.
     * Плотность воздуха во впуске считается из давления и температуры по
     * уравнению состояния.
     *
     * Это ОЦЕНКА. Коэффициент наполнения на самом деле гуляет с оборотами и
     * нагрузкой, а здесь он один на все режимы, поэтому число стоит сверить с
     * заправками и подкрутить. Зато ведёт себя оценка честно: растёт с газом,
     * падает при сбросе, и на стоянке показывает настоящий холостой расход.
     *
     * @param mapKpa абсолютное давление во впуске (PID 0x0B), кПа.
     * @param intakeTempC температура воздуха на впуске (PID 0x0F), °C.
     * @param displacementL рабочий объём двигателя, л.
     * @param volumetricEfficiency коэффициент наполнения, доля (0.5-1.0).
     */
    fun mafFromMap(
        rpm: Double,
        mapKpa: Double,
        intakeTempC: Double,
        displacementL: Double,
        volumetricEfficiency: Double = DEFAULT_VOLUMETRIC_EFFICIENCY,
    ): Double? {
        if (rpm <= 0 || mapKpa <= 0 || displacementL <= 0) return null
        val kelvin = intakeTempC + 273.15
        if (kelvin <= 0) return null
        val efficiency = volumetricEfficiency.coerceIn(MIN_EFFICIENCY, MAX_EFFICIENCY)
        // Плотность воздуха во впуске, кг/м3.
        val density = mapKpa * 1000.0 / (AIR_GAS_CONSTANT * kelvin)
        // Объём, прокачиваемый за минуту, м3: за два оборота - один объём.
        val cubicMetresPerMinute = rpm / 2.0 * displacementL * efficiency / 1000.0
        // кг/мин -> г/с.
        return density * cubicMetresPerMinute * 1000.0 / 60.0
    }

    /** Удельная газовая постоянная сухого воздуха, Дж/(кг·К). */
    private const val AIR_GAS_CONSTANT = 287.05

    /**
     * Коэффициент наполнения по умолчанию. 0.8 - середина для обычного
     * атмосферного мотора без наддува; точное значение у каждого своё, и его
     * имеет смысл подогнать по заправкам.
     */
    const val DEFAULT_VOLUMETRIC_EFFICIENCY = 0.80

    const val MIN_EFFICIENCY = 0.40
    const val MAX_EFFICIENCY = 1.20

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
