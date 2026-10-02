package com.obdscanner.core

import com.obdscanner.core.obd.ObdUnit
import com.obdscanner.core.obd.UnitConversion
import com.obdscanner.core.obd.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пересчёт в имперские единицы. Шина всегда отдаёт метрику - это только показ. */
class UnitConversionTest {

    /** В метрике ничего не пересчитывается. */
    @Test
    fun metricPassesValuesThrough() {
        val v = UnitConversion.convert(100.0, ObdUnit.KMH, UnitSystem.METRIC)
        assertEquals(100.0, v.value, 1e-9)
        assertEquals(ObdUnit.KMH.label, v.unit)
    }

    /** Скорость, пробег и температура. */
    @Test
    fun speedDistanceAndTemperature() {
        assertEquals(62.14, UnitConversion.convert(100.0, ObdUnit.KMH, UnitSystem.IMPERIAL).value, 0.01)
        assertEquals("mph", UnitConversion.convert(100.0, ObdUnit.KMH, UnitSystem.IMPERIAL).unit)
        assertEquals(62.14, UnitConversion.convert(100.0, ObdUnit.KM, UnitSystem.IMPERIAL).value, 0.01)
        assertEquals(194.0, UnitConversion.convert(90.0, ObdUnit.CELSIUS, UnitSystem.IMPERIAL).value, 1e-9)
        assertEquals(32.0, UnitConversion.convert(0.0, ObdUnit.CELSIUS, UnitSystem.IMPERIAL).value, 1e-9)
        assertEquals(-40.0, UnitConversion.convert(-40.0, ObdUnit.CELSIUS, UnitSystem.IMPERIAL).value, 1e-9)
    }

    /** Давление и расход воздуха. */
    @Test
    fun pressureAndAirFlow() {
        assertEquals(14.5, UnitConversion.convert(100.0, ObdUnit.KPA, UnitSystem.IMPERIAL).value, 0.01)
        assertEquals(1.32, UnitConversion.convert(10.0, ObdUnit.GRAM_PER_SEC, UnitSystem.IMPERIAL).value, 0.01)
    }

    /** Расход переворачивается: 9.4 л/100км это 25 миль на галлон. */
    @Test
    fun consumptionIsInverted() {
        val mpg = UnitConversion.convert(9.4086, ObdUnit.LITRE_PER_100KM, UnitSystem.IMPERIAL)
        assertEquals(25.0, mpg.value, 0.01)
        assertEquals("mpg", mpg.unit)
        assertTrue(UnitConversion.isInverted(ObdUnit.LITRE_PER_100KM, UnitSystem.IMPERIAL))
        assertFalse(UnitConversion.isInverted(ObdUnit.KMH, UnitSystem.IMPERIAL))
        assertFalse(UnitConversion.isInverted(ObdUnit.LITRE_PER_100KM, UnitSystem.METRIC))
        // Нулевой расход не превращается в бесконечность.
        assertEquals(0.0, UnitConversion.convert(0.0, ObdUnit.LITRE_PER_100KM, UnitSystem.IMPERIAL).value, 1e-9)
    }

    /** Литры в галлоны - для итогов поездки. */
    @Test
    fun volumeConversion() {
        assertEquals(10.0, UnitConversion.litres(10.0, UnitSystem.METRIC).value, 1e-9)
        assertEquals(2.64, UnitConversion.litres(10.0, UnitSystem.IMPERIAL).value, 0.01)
    }

    /** Обороты и проценты одинаковы в любой системе. */
    @Test
    fun dimensionlessUnitsAreUntouched() {
        assertEquals(2500.0, UnitConversion.convert(2500.0, ObdUnit.RPM, UnitSystem.IMPERIAL).value, 1e-9)
        assertEquals(42.0, UnitConversion.convert(42.0, ObdUnit.PERCENT, UnitSystem.IMPERIAL).value, 1e-9)
        assertEquals(14.2, UnitConversion.convert(14.2, ObdUnit.VOLT, UnitSystem.IMPERIAL).value, 1e-9)
    }
}
