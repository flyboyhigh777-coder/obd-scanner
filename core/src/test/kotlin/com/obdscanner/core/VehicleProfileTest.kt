package com.obdscanner.core

import com.obdscanner.core.dtc.ManufacturerDtcFamily
import com.obdscanner.core.vehicle.TyreSize
import com.obdscanner.core.vehicle.VehicleProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Размер шины и профиль машины: от них зависят запас хода, мощность и шкалы. */
class VehicleProfileTest {

    /** Разбор типоразмера в разных написаниях. */
    @Test
    fun tyreSizeParsing() {
        val tyre = TyreSize.parse("245/70R16")!!
        assertEquals(245, tyre.widthMm)
        assertEquals(70, tyre.profilePercent)
        assertEquals(16.0, tyre.rimInches, 1e-9)
        assertEquals(tyre, TyreSize.parse("245/70 R16"))
        assertEquals(tyre, TyreSize.parse("шины 245/70 r16 всесезонка"))
        assertNotNull(TyreSize.parse("245/70R16C"))
        assertNull(TyreSize.parse("шины обычные"))
        assertNull(TyreSize.parse("900/70R16"))
    }

    /** Наружный диаметр и длина окружности 245/70 R16. */
    @Test
    fun tyreGeometry() {
        val tyre = TyreSize(245, 70, 16.0)
        // 16" = 406.4 мм плюс две высоты профиля по 171.5 мм
        assertEquals(749.4, tyre.diameterMm, 0.1)
        assertEquals(2.354, tyre.circumferenceM, 0.002)
        // Катящееся колесо проезжает за оборот на 3% меньше
        assertEquals(2.284, tyre.rollingCircumferenceM, 0.002)
        assertEquals(437.8, tyre.revolutionsPerKm, 1.0)
    }

    /** Шина больше заводской - спидометр занижает. */
    @Test
    fun speedCorrectionForBiggerTyre() {
        val factory = TyreSize(245, 70, 16.0)
        val bigger = TyreSize(265, 70, 16.0)
        val factor = bigger.speedFactorAgainst(factory)
        assertTrue("больше единицы: $factor", factor > 1.0)
        // 265/70R16 выше 245/70R16 примерно на 3.7% по диаметру
        assertEquals(1.037, factor, 0.005)
        assertEquals(1.0, factory.speedFactorAgainst(factory), 1e-9)
    }

    /** Профиль Патриота: связность чисел и ссылка на таблицу кодов ЭБУ. */
    @Test
    fun patriotProfile() {
        val patriot = VehicleProfiles.UAZ_PATRIOT_2016
        assertEquals("uaz_patriot_2016", patriot.id)
        assertEquals(2.693, patriot.displacementL, 1e-9)
        assertEquals(ManufacturerDtcFamily.ME17_9_7, patriot.manufacturerDtcFamily)
        assertEquals(2225.0, patriot.totalMassKg, 1e-9)
        // Шины заводские - поправки спидометра нет
        assertEquals(1.0, patriot.speedCorrection, 1e-9)
        // Предел шкалы расхода воздуха для 2.7 л на красной зоне - порядка сотни г/с,
        // а не 300, как в общем профиле: шкала должна быть по машине.
        assertTrue("расход воздуха ${patriot.maxMafGramsPerSecond}", patriot.maxMafGramsPerSecond in 100.0..180.0)
        assertTrue(patriot.notes.isNotEmpty())
    }

    /** Профиль по умолчанию и поиск по идентификатору. */
    @Test
    fun profileLookup() {
        assertEquals(VehicleProfiles.UAZ_PATRIOT_2016, VehicleProfiles.byId("uaz_patriot_2016"))
        assertEquals(VehicleProfiles.GENERIC, VehicleProfiles.byId("нет такого"))
        assertEquals(VehicleProfiles.GENERIC, VehicleProfiles.byId(null))
        assertNull(VehicleProfiles.GENERIC.manufacturerDtcFamily)
    }
}
