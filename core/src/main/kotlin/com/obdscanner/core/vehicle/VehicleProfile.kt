package com.obdscanner.core.vehicle

import com.obdscanner.core.dtc.ManufacturerDtcFamily
import com.obdscanner.core.elm.ObdText

/**
 * Параметры конкретной машины. Шина OBD-II ничего из этого не сообщает, а
 * считать без этого нельзя: запас хода - без объёма бака, мощность по разгону -
 * без массы, передачу - без длины окружности колеса, шкалы приборов - без
 * красной зоны и максимальной скорости.
 *
 * Поэтому профиль - это набор значений ПО УМОЛЧАНИЮ, которые пользователь
 * правит под свою машину. Комплектации одной и той же модели различаются
 * (у Патриота, например, до рестайлинга бак был из двух половин по 36 литров),
 * и выдавать одно число за истину для всех было бы неправдой.
 *
 * Аэродинамика и сопротивление качению нужны только для оценки мощности и
 * заведомо приблизительны - см. [com.obdscanner.core.perf.DynoEstimator].
 */
data class VehicleProfile(
    val id: String,
    val name: String,
    val engine: String,
    val displacementL: Double,
    val cylinders: Int,
    /** Код типа топлива как в PID 0x51 (1 - бензин, 4 - дизель). */
    val fuelTypeCode: Int,
    val tankLitres: Double,
    /** Снаряжённая масса, кг. */
    val curbMassKg: Double,
    /** Водитель, пассажиры, груз - прибавка к снаряжённой массе для расчётов. */
    val payloadKg: Double,
    val tyre: TyreSize,
    /** Шина, под которую ЭБУ считает скорость (заводской размер). */
    val factoryTyre: TyreSize,
    val rpmRedline: Double,
    val maxSpeedKmh: Double,
    val ecuName: String,
    /** Семейство кодов производителя, если известно (см. ManufacturerDtc). */
    val manufacturerDtcFamily: String?,
    val frontalAreaM2: Double,
    val dragCoefficient: Double,
    val rollingResistance: Double,
    val ru: String,
    val en: String
) {
    /** Полная расчётная масса. */
    val totalMassKg: Double get() = curbMassKg + payloadKg

    val notes: String get() = ObdText.pick(ru, en)

    /**
     * Поправка спидометра: во сколько раз настоящая скорость отличается от той,
     * что отдаёт ЭБУ, если шины не заводские.
     */
    val speedCorrection: Double get() = tyre.speedFactorAgainst(factoryTyre)

    /**
     * Разумный предел шкалы расхода воздуха, г/с. Двигатель за два оборота
     * прокачивает рабочий объём, поэтому на красной зоне расход воздуха
     * ограничен сверху объёмом и плотностью воздуха; наддув учитываем запасом.
     */
    val maxMafGramsPerSecond: Double
        get() = displacementL / 2.0 * (rpmRedline / 60.0) * AIR_DENSITY_G_PER_L * VOLUMETRIC_EFFICIENCY

    companion object {
        private const val AIR_DENSITY_G_PER_L = 1.2
        private const val VOLUMETRIC_EFFICIENCY = 1.1
    }
}

object VehicleProfiles {

    /**
     * УАЗ Патриот 2016 года и новее (кузов 3163 после рестайлинга).
     *
     * Двигатель ЗМЗ-409 (2.7 л, 4 цилиндра, бензин), блок управления
     * Bosch ME17.9.7 - именно он даёт коды P1xxx, которых нет в стандартных
     * справочниках, поэтому профиль ссылается на таблицу ME17.9.7.
     *
     * Числа - типовые для этой машины, но комплектации различались: бак на
     * рестайлинге единый, до него - две половины по 36 л; шины бывают и
     * 245/70 R16, и 245/60 R18. Всё это правится на вкладке "Авто", и профиль
     * существует именно для того, чтобы поправить один раз и забыть.
     */
    val UAZ_PATRIOT_2016 = VehicleProfile(
        id = "uaz_patriot_2016",
        name = "УАЗ Патриот 2016+",
        engine = "ЗМЗ-409 (2.7 л)",
        displacementL = 2.693,
        cylinders = 4,
        fuelTypeCode = 1,
        tankLitres = 68.0,
        curbMassKg = 2125.0,
        payloadKg = 100.0,
        tyre = TyreSize(245, 70, 16.0),
        factoryTyre = TyreSize(245, 70, 16.0),
        rpmRedline = 4800.0,
        maxSpeedKmh = 150.0,
        ecuName = "Bosch ME17.9.7",
        manufacturerDtcFamily = ManufacturerDtcFamily.ME17_9_7,
        // Рамный внедорожник: площадь лобовой проекции около 3.2 м², кузов
        // "кирпичом" - Cx порядка 0.5. Числа грубые и влияют только на оценку
        // мощности, причём тем сильнее, чем выше скорость.
        frontalAreaM2 = 3.2,
        dragCoefficient = 0.50,
        rollingResistance = 0.015,
        ru = "Значения по умолчанию для Патриота 2016+. Проверьте бак, массу и " +
            "размер шин по своей машине: от них зависят запас хода, оценка мощности и шкалы приборов.",
        en = "Defaults for a 2016+ Patriot. Check tank, mass and tyre size against your own car: " +
            "range, power estimate and gauge scales depend on them."
    )

    /** Профиль "неизвестная машина": шкалы пошире, расчёты, где нужна масса, отключены. */
    val GENERIC = VehicleProfile(
        id = "generic",
        name = "Любая машина",
        engine = "—",
        displacementL = 2.0,
        cylinders = 4,
        fuelTypeCode = 1,
        tankLitres = 50.0,
        curbMassKg = 1500.0,
        payloadKg = 100.0,
        tyre = TyreSize(205, 55, 16.0),
        factoryTyre = TyreSize(205, 55, 16.0),
        rpmRedline = 6500.0,
        maxSpeedKmh = 200.0,
        ecuName = "—",
        manufacturerDtcFamily = null,
        frontalAreaM2 = 2.2,
        dragCoefficient = 0.32,
        rollingResistance = 0.012,
        ru = "Общий профиль. Впишите бак, массу и шины своей машины - иначе запас хода и " +
            "оценка мощности будут считаться по условным числам.",
        en = "Generic profile. Enter your car's tank, mass and tyres - otherwise range and power " +
            "estimates are based on placeholder numbers."
    )

    val ALL: List<VehicleProfile> = listOf(UAZ_PATRIOT_2016, GENERIC)

    fun byId(id: String?): VehicleProfile = ALL.firstOrNull { it.id == id } ?: GENERIC
}
