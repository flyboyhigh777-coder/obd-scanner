package com.obdscanner.core

import com.obdscanner.core.obd.Pids
import com.obdscanner.core.trip.TripRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Итоги поездки: пробег, расход, средняя скорость, выгрузка в CSV. */
class TripRecorderTest {

    /** Ровная езда 100 км/ч ровно час - значит 100 км. */
    @Test
    /** пробег интегрируется по скорости. */
    fun distanceIsIntegratedFromSpeed() {
        val trip = TripRecorder(maxGapMillis = 2_000)
        var t = 0L
        repeat(3601) {
            trip.add(t, mapOf(Pids.SPEED.id to 100.0))
            t += 1000
        }
        val stats = trip.stats()
        assertEquals(100.0, stats.distanceKm, 0.01)
        assertEquals(100.0, stats.maxSpeedKmh, 1e-9)
        assertEquals(100.0, stats.averageMovingSpeedKmh, 0.01)
        assertEquals(0L, stats.idleMillis)
        assertEquals(3_600_000L, stats.durationMillis)
    }

    @Test
    /** стоянка идёт в простой, а не в пробег. */
    fun standstillCountsAsIdle() {
        val trip = TripRecorder()
        trip.add(0, mapOf(Pids.SPEED.id to 0.0))
        trip.add(1000, mapOf(Pids.SPEED.id to 0.0))
        trip.add(2000, mapOf(Pids.SPEED.id to 0.0))
        val stats = trip.stats()
        assertEquals(0.0, stats.distanceKm, 1e-9)
        assertEquals(2000L, stats.idleMillis)
        assertEquals(0.0, stats.averageMovingSpeedKmh, 1e-9)
    }

    @Test
    /** разрыв в данных не дорисовывается. */
    fun dataGapIsNotInterpolated() {
        val trip = TripRecorder(maxGapMillis = 5_000)
        trip.add(0, mapOf(Pids.SPEED.id to 90.0))
        trip.add(1000, mapOf(Pids.SPEED.id to 90.0))
        // Минута без данных - приложение было свёрнуто.
        trip.add(61_000, mapOf(Pids.SPEED.id to 90.0))
        trip.add(62_000, mapOf(Pids.SPEED.id to 90.0))
        // Считаются только два отрезка по секунде, а не вся минута.
        assertEquals(90.0 * 2 / 3600.0, trip.stats().distanceKm, 1e-6)
    }

    @Test
    /** расход считается из расхода воздуха, если нет прямого PID. */
    fun fuelFromMafWhenNoDirectPid() {
        val trip = TripRecorder()
        var t = 0L
        // 20 г/с воздуха на 100 км/ч в течение часа.
        repeat(3601) {
            trip.add(t, mapOf(Pids.SPEED.id to 100.0, Pids.MAF.id to 20.0))
            t += 1000
        }
        val stats = trip.stats()
        // 20 г/с -> ~6.64 л/ч; за час столько же литров на 100 км.
        assertEquals(6.64, stats.fuelLitres, 0.02)
        assertEquals(6.64, stats.consumptionL100Km!!, 0.03)
    }

    @Test
    /** прямой PID расхода важнее расчёта по воздуху. */
    fun directFuelRatePidWins() {
        val trip = TripRecorder()
        var t = 0L
        repeat(3601) {
            trip.add(
                t,
                mapOf(Pids.SPEED.id to 100.0, Pids.MAF.id to 20.0, Pids.FUEL_RATE.id to 9.0)
            )
            t += 1000
        }
        assertEquals(9.0, trip.stats().fuelLitres, 0.02)
    }

    @Test
    /** максимумы запоминаются. */
    fun maximaAreTracked() {
        val trip = TripRecorder()
        trip.add(0, mapOf(Pids.RPM.id to 900.0, Pids.COOLANT_TEMP.id to 60.0, Pids.SPEED.id to 0.0))
        trip.add(500, mapOf(Pids.RPM.id to 5400.0, Pids.COOLANT_TEMP.id to 103.0, Pids.SPEED.id to 60.0))
        trip.add(1000, mapOf(Pids.RPM.id to 2000.0, Pids.COOLANT_TEMP.id to 95.0, Pids.SPEED.id to 80.0))
        val stats = trip.stats()
        assertEquals(5400.0, stats.maxRpm, 1e-9)
        assertEquals(103.0, stats.maxCoolantC!!, 1e-9)
        assertEquals(80.0, stats.maxSpeedKmh, 1e-9)
    }

    @Test
    /** пустая запись не падает. */
    fun emptyRecorderIsSafe() {
        val stats = TripRecorder().stats()
        assertEquals(0, stats.sampleCount)
        assertEquals(0.0, stats.distanceKm, 1e-9)
        assertNull(stats.consumptionL100Km)
    }

    @Test
    /** CSV содержит только заполненные столбцы и точку как разделитель. */
    fun csvHasOnlyFilledColumnsAndDotDecimal() {
        val trip = TripRecorder()
        trip.add(1_000_000, mapOf(Pids.SPEED.id to 42.0, Pids.RPM.id to 2500.5))
        trip.add(1_000_500, mapOf(Pids.SPEED.id to 43.0, Pids.RPM.id to 2600.0))
        val csv = trip.toCsv()
        val lines = csv.trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].startsWith("time_s;"))
        // Столбцов ровно три: время, обороты, скорость - остальных PID в записи нет.
        assertEquals(3, lines[0].split(';').size)
        assertFalse(lines[0].contains("Coolant"))
        // Время считается от первого замера, а десятичный разделитель - точка.
        assertTrue(lines[1], lines[1].startsWith("0.00;"))
        assertTrue(lines[2], lines[2].startsWith("0.50;"))
        assertTrue(lines[1], lines[1].contains("2501") || lines[1].contains("2500"))
    }
}
