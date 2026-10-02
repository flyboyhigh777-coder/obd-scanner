package com.obdscanner.core

import com.obdscanner.core.obd.FuelEconomy
import com.obdscanner.core.obd.FuelType
import com.obdscanner.core.obd.MonitorStatus
import com.obdscanner.core.obd.ObdStandard
import com.obdscanner.core.obd.PidSupport
import com.obdscanner.core.obd.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формулы пересчёта PID (SAE J1979). Числа в проверках взяты из примеров,
 * которые встречаются в документации и легко пересчитываются на бумаге:
 * ошибка в коэффициенте сразу видна.
 */
class PidDecoderTest {

    @Test
    /** обороты - четверть такта на бит. */
    fun rpmIsQuarterOfRawCounts() {
        // 0x1AF8 = 6904; 6904 / 4 = 1726 об/мин
        assertEquals(1726.0, Pids.RPM.decode(intArrayOf(0x1A, 0xF8))!!, 1e-9)
        assertEquals(0.0, Pids.RPM.decode(intArrayOf(0x00, 0x00))!!, 1e-9)
        assertEquals(16383.75, Pids.RPM.decode(intArrayOf(0xFF, 0xFF))!!, 1e-9)
    }

    @Test
    /** температуры со смещением 40 градусов. */
    fun temperaturesUseFortyDegreeOffset() {
        assertEquals(83.0, Pids.COOLANT_TEMP.decode(intArrayOf(123))!!, 1e-9)
        assertEquals(-40.0, Pids.COOLANT_TEMP.decode(intArrayOf(0))!!, 1e-9)
        assertEquals(20.0, Pids.INTAKE_TEMP.decode(intArrayOf(60))!!, 1e-9)
        assertEquals(90.0, Pids.OIL_TEMP.decode(intArrayOf(130))!!, 1e-9)
    }

    @Test
    /** проценты - полный байт это сто процентов. */
    fun percentagesScaleFullByteToHundred() {
        assertEquals(100.0, Pids.ENGINE_LOAD.decode(intArrayOf(255))!!, 1e-9)
        assertEquals(0.0, Pids.THROTTLE.decode(intArrayOf(0))!!, 1e-9)
        assertEquals(50.196, Pids.THROTTLE.decode(intArrayOf(128))!!, 1e-3)
    }

    @Test
    /** коррекция топливоподачи бывает отрицательной. */
    fun fuelTrimCanBeNegative() {
        assertEquals(0.0, Pids.SHORT_FUEL_TRIM_1.decode(intArrayOf(128))!!, 1e-9)
        assertEquals(-100.0, Pids.SHORT_FUEL_TRIM_1.decode(intArrayOf(0))!!, 1e-9)
        assertEquals(10.9375, Pids.LONG_FUEL_TRIM_1.decode(intArrayOf(142))!!, 1e-4)
    }

    @Test
    /** расход воздуха и напряжение. */
    fun massAirFlowAndModuleVoltage() {
        // 0x0F A0 = 4000 -> 40.00 г/с
        assertEquals(40.0, Pids.MAF.decode(intArrayOf(0x0F, 0xA0))!!, 1e-9)
        // 0x36 B0 = 14000 -> 14.0 В
        assertEquals(14.0, Pids.CONTROL_MODULE_VOLTAGE.decode(intArrayOf(0x36, 0xB0))!!, 1e-9)
    }

    @Test
    /** угол опережения и лямбда. */
    fun timingAdvanceAndEquivalenceRatio() {
        assertEquals(0.0, Pids.TIMING_ADVANCE.decode(intArrayOf(128))!!, 1e-9)
        assertEquals(10.0, Pids.TIMING_ADVANCE.decode(intArrayOf(148))!!, 1e-9)
        assertEquals(1.0, Pids.EQUIVALENCE_RATIO.decode(intArrayOf(0x80, 0x00))!!, 1e-9)
    }

    @Test
    /** температура катализатора со смещением и делителем. */
    fun catalystTemperatureScaling() {
        // (0x18 0x1C = 6172) / 10 - 40 = 577.2 °C
        assertEquals(577.2, Pids.CATALYST_TEMP_B1S1.decode(intArrayOf(0x18, 0x1C))!!, 1e-9)
    }

    @Test
    /** короткий ответ не превращается в нулевое значение. */
    fun truncatedResponseDecodesToNull() {
        // Двухбайтному PID пришёл один байт - честнее ничего не показать,
        // чем показать "0 об/мин" на работающем двигателе.
        assertNull(Pids.RPM.decode(intArrayOf(0x1A)))
        assertNull(Pids.MAF.decode(IntArray(0)))
        assertNull(Pids.COOLANT_TEMP.decode(IntArray(0)))
    }

    @Test
    /** карта поддерживаемых PID разбирается побитово. */
    fun supportedPidBitmaskIsDecoded() {
        // BE 1F A8 13: старший бит первого байта - PID 0x01.
        val supported = PidSupport.decode(0x00, intArrayOf(0xBE, 0x1F, 0xA8, 0x13))
        assertTrue(0x01 in supported)
        assertTrue(0x0C in supported)
        assertTrue(0x0D in supported)
        assertFalse(0x02 in supported)
        assertFalse(0x08 in supported)
        assertEquals(0x20 in supported, PidSupport.hasNextRange(0x00, supported))
    }

    @Test
    /** карта диапазона 0x20 сдвигает номера. */
    fun supportedPidRangeIsOffset() {
        val supported = PidSupport.decode(0x20, intArrayOf(0x80, 0x00, 0x00, 0x00))
        assertEquals(setOf(0x21), supported)
    }

    @Test
    /** кодирование и разбор карты согласованы. */
    fun supportedPidEncodeDecodeRoundTrip() {
        val pids = setOf(0x01, 0x04, 0x0C, 0x0D, 0x11, 0x1F, 0x20)
        assertEquals(pids, PidSupport.decode(0x00, PidSupport.encode(0x00, pids)))
    }

    @Test
    /** статус тестов - лампа, число ошибок и готовность. */
    fun monitorStatusReportsMilAndReadiness() {
        // 0x83: лампа горит, 3 ошибки. B=0x07 - поддержаны три общих теста.
        val status = MonitorStatus.decode(intArrayOf(0x83, 0x07, 0xE5, 0x01))!!
        assertTrue(status.milOn)
        assertEquals(3, status.dtcCount)
        assertFalse(status.compressionIgnition)
        assertTrue(status.monitors.first().supported)
        // D=0x01 - бит 0 выставлен, значит тест катализатора НЕ завершён.
        assertFalse(status.readyForInspection)
        assertEquals(1, status.incomplete.size)
    }

    @Test
    /** лампа погашена и ошибок нет. */
    fun monitorStatusWithoutFaults() {
        val status = MonitorStatus.decode(intArrayOf(0x00, 0x07, 0x00, 0x00))!!
        assertFalse(status.milOn)
        assertEquals(0, status.dtcCount)
        assertTrue(status.readyForInspection)
    }

    @Test
    /** дизель получает свой набор тестов. */
    fun compressionIgnitionHasItsOwnMonitors() {
        val status = MonitorStatus.decode(intArrayOf(0x00, 0x0F, 0x03, 0x00))!!
        assertTrue(status.compressionIgnition)
        assertTrue(status.monitors.any { it.en.contains("NMHC") })
        assertFalse(status.monitors.any { it.en.contains("Catalyst", ignoreCase = true) && it.en.contains("Heated") })
    }

    @Test
    /** расход топлива из расхода воздуха. */
    fun fuelRateFromMassAirFlow() {
        // 10 г/с бензина -> 36000 г/ч воздуха / 14.7 / 737 г/л ~ 3.32 л/ч
        val lph = FuelEconomy.litresPerHourFromMaf(10.0)
        assertEquals(3.32, lph, 0.01)
        // На 90 км/ч это около 3.7 л/100км
        assertEquals(3.69, FuelEconomy.litresPer100Km(lph, 90.0)!!, 0.01)
        // На стоянке литры на сотню не считаются
        assertNull(FuelEconomy.litresPer100Km(lph, 0.0))
        // Дизель плотнее и расходует меньше литров на ту же массу воздуха
        assertTrue(FuelEconomy.litresPerHourFromMaf(10.0, diesel = true) < lph)
    }

    @Test
    /** справочники типов топлива и стандартов. */
    fun fuelTypeAndStandardNames() {
        assertTrue(FuelType.name(1).isNotEmpty())
        assertTrue(FuelType.isDiesel(4))
        assertFalse(FuelType.isDiesel(1))
        assertTrue(ObdStandard.name(6).isNotEmpty())
        assertTrue(ObdStandard.name(250).contains("250"))
    }

    @Test
    /** в каталоге нет двух PID с одним номером. */
    fun pidCatalogHasNoDuplicates() {
        assertEquals(Pids.ALL.size, Pids.ALL.map { it.id }.toSet().size)
        // И у каждого PID на приборке по умолчанию есть описание в каталоге.
        Pids.DEFAULT_DASHBOARD.forEach { id -> assertTrue(id in Pids.byId) }
    }
}
