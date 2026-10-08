package com.obdscanner.core

import com.obdscanner.core.electrical.Battery
import com.obdscanner.core.electrical.BatteryMonitor
import com.obdscanner.core.electrical.BatteryVerdict
import com.obdscanner.core.electrical.CrankVerdict
import com.obdscanner.core.electrical.LeakVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryTest {

    /** Узлы таблицы заряда должны давать ровно табличные значения. */
    @Test
    fun chargeTableHitsItsOwnNodes() {
        assertEquals(100, Battery.restingChargePercent(12.73))
        assertEquals(90, Battery.restingChargePercent(12.62))
        assertEquals(50, Battery.restingChargePercent(12.20))
        assertEquals(0, Battery.restingChargePercent(11.31))
    }

    /** Между узлами - линейно, и без выходов за 0..100. */
    @Test
    fun chargeTableInterpolatesAndClamps() {
        val mid = Battery.restingChargePercent(12.56)
        assertTrue("между 80 и 90: $mid", mid in 80..90)
        assertEquals(100, Battery.restingChargePercent(13.5))
        assertEquals(0, Battery.restingChargePercent(9.0))
    }

    /**
     * Одно и то же напряжение значит разное при заглушенном и работающем
     * моторе - это главная развилка во всей оценке.
     */
    @Test
    fun sameVoltageMeansDifferentThingsWithEngineOnAndOff() {
        assertEquals(BatteryVerdict.RESTING_OK, Battery.verdict(12.6, engineRunning = false))
        assertEquals(BatteryVerdict.NOT_CHARGING, Battery.verdict(12.6, engineRunning = true))
    }

    @Test
    fun chargingThresholds() {
        assertEquals(BatteryVerdict.CHARGE_OK, Battery.verdict(14.2, engineRunning = true))
        assertEquals(BatteryVerdict.CHARGE_WEAK, Battery.verdict(13.3, engineRunning = true))
        assertEquals(BatteryVerdict.OVERCHARGE, Battery.verdict(15.1, engineRunning = true))
    }

    @Test
    fun restingThresholds() {
        assertEquals(BatteryVerdict.DEEP_DISCHARGE, Battery.verdict(11.5, engineRunning = false))
        assertEquals(BatteryVerdict.DISCHARGED, Battery.verdict(12.0, engineRunning = false))
        assertEquals(BatteryVerdict.PARTIAL, Battery.verdict(12.35, engineRunning = false))
    }

    /** Ноль и мусор не должны превращаться в приговор "глубокий разряд". */
    @Test
    fun noDataIsNotAVerdict() {
        assertEquals(BatteryVerdict.UNKNOWN, Battery.verdict(0.0, engineRunning = false))
    }

    @Test
    fun crankThresholds() {
        assertEquals(CrankVerdict.GOOD, Battery.crankVerdict(10.8))
        assertEquals(CrankVerdict.FAIR, Battery.crankVerdict(10.0))
        assertEquals(CrankVerdict.WEAK, Battery.crankVerdict(9.2))
    }

    /** Провал при прокрутке: ловим минимум, длительность и глубину. */
    @Test
    fun monitorCatchesCrankingDip() {
        val m = BatteryMonitor()
        // Стоим, мотор не работает.
        m.add(0, 12.6, 0.0)
        m.add(100, 12.6, 0.0)
        // Прокрутка стартером: напряжение валится.
        m.add(200, 11.2, 0.0)
        m.add(300, 10.1, 0.0)
        m.add(400, 9.9, 0.0)
        m.add(500, 10.4, 200.0)
        // Мотор завёлся, генератор поднял напряжение.
        m.add(600, 13.9, 800.0)

        val crank = m.cranking
        assertNotNull("просадка должна быть поймана", crank)
        assertEquals(9.9, crank!!.minVolts, 1e-9)
        assertEquals(12.6, crank.restVolts, 1e-9)
        assertEquals(2.7, crank.dropVolts, 1e-9)
        assertEquals(400L, crank.durationMillis)
        assertEquals(CrankVerdict.FAIR, crank.verdict)
    }

    /**
     * Долгий провал - это не пуск, а разряд под нагрузкой, и выдавать его за
     * результат теста стартера нельзя.
     */
    @Test
    fun longSagIsNotCranking() {
        val m = BatteryMonitor(maxDipMillis = 5_000)
        m.add(0, 12.5, 0.0)
        m.add(1_000, 11.0, 0.0)
        m.add(20_000, 10.8, 0.0)
        m.add(21_000, 12.4, 0.0)
        assertNull(m.cranking)
    }

    /** Заряд в процентах при работающем моторе бессмыслен - его не показываем. */
    @Test
    fun chargePercentOnlyWhenEngineIsOff() {
        val m = BatteryMonitor()
        m.add(0, 12.6, 0.0)
        assertEquals(80, m.restingChargePercent()!! / 10 * 10)

        val running = BatteryMonitor()
        running.add(0, 14.2, 1_500.0)
        assertNull(running.restingChargePercent())
    }

    @Test
    fun monitorTracksRangeAndResets() {
        val m = BatteryMonitor()
        m.add(0, 12.6, 0.0)
        m.add(100, 14.3, 900.0)
        m.add(200, 13.8, 900.0)
        assertEquals(12.6, m.minVolts!!, 1e-9)
        assertEquals(14.3, m.maxVolts!!, 1e-9)
        assertEquals(1.7, m.spreadVolts()!!, 1e-9)
        assertEquals(BatteryVerdict.CHARGE_OK, m.verdict())

        m.reset()
        assertNull(m.minVolts)
        assertNull(m.cranking)
        assertEquals(BatteryVerdict.UNKNOWN, m.verdict())
    }

    /** Нулевые и нечисловые замеры адаптера не должны портить минимум. */
    @Test
    fun junkSamplesAreIgnored() {
        val m = BatteryMonitor()
        m.add(0, 12.6, 0.0)
        m.add(100, 0.0, 0.0)
        m.add(200, Double.NaN, 0.0)
        assertEquals(12.6, m.minVolts!!, 1e-9)
        assertEquals(1, m.samples.size)
    }

    /**
     * Ток утечки приложение не меряет - его приносят клещами. Проверяем, что
     * арифметика по принесённому числу честная.
     */
    @Test
    fun leakArithmetic() {
        // 95 А·ч, заряд 100%, утечка 50 мА: до 50% уходит 47.5 А·ч,
        // это 950 часов, почти сорок суток.
        val days = Battery.daysUntilFlat(batteryAh = 95.0, chargePercent = 100, milliAmps = 50.0)!!
        assertEquals(39.6, days, 0.2)

        // Утечка в ампер съедает ту же половину за двое суток.
        val fast = Battery.daysUntilFlat(batteryAh = 95.0, chargePercent = 100, milliAmps = 1000.0)!!
        assertEquals(1.98, fast, 0.05)
    }

    /** Разряженная батарея "простоит" ноль - а не отрицательное время. */
    @Test
    fun alreadyFlatBatteryLastsZero() {
        assertEquals(0.0, Battery.daysUntilFlat(95.0, 40, 50.0)!!, 1e-9)
    }

    /** Нулевой и бессмысленный ток - это не ответ, а отсутствие ответа. */
    @Test
    fun noCurrentMeansNoAnswer() {
        assertNull(Battery.daysUntilFlat(95.0, 100, 0.0))
        assertNull(Battery.daysUntilFlat(0.0, 100, 50.0))
    }

    @Test
    fun leakThresholds() {
        assertEquals(LeakVerdict.NORMAL, Battery.leakVerdict(30.0))
        assertEquals(LeakVerdict.ELEVATED, Battery.leakVerdict(65.0))
        assertEquals(LeakVerdict.HIGH, Battery.leakVerdict(250.0))
    }
}
