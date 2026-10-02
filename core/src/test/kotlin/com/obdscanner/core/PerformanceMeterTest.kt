package com.obdscanner.core

import com.obdscanner.core.perf.DynoEstimator
import com.obdscanner.core.perf.PerformanceMeter
import com.obdscanner.core.perf.SplitKind
import com.obdscanner.core.vehicle.VehicleProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Замеры разгона проверяются на равноускоренном движении: для него время до
 * каждой скорости и каждой дистанции считается на бумаге, так что ошибка в
 * интегрировании или интерполяции сразу видна.
 */
class PerformanceMeterTest {

    private val accel = 3.0 // м/с²

    /** Скорость, км/ч, при равноускоренном разгоне с места. */
    private fun speedAt(seconds: Double) = accel * seconds * 3.6

    /** Прогоняет разгон длительностью [seconds] с шагом опроса [stepMillis]. */
    private fun driveAcceleration(
        meter: PerformanceMeter,
        seconds: Double,
        stepMillis: Long = 100,
        round: Boolean = false,
        rpm: Double? = null
    ) {
        var t = 0L
        val end = (seconds * 1000).toLong()
        while (t <= end) {
            val v = speedAt(t / 1000.0)
            meter.add(t, if (round) kotlin.math.round(v) else v, rpm)
            t += stepMillis
        }
    }

    /** 0-100 и 0-60 на равноускоренном разгоне совпадают с расчётом. */
    @Test
    fun accelerationSplitsMatchPhysics() {
        val meter = PerformanceMeter()
        driveAcceleration(meter, 20.0)
        // Торможение в конце, чтобы заезд закрылся
        meter.add(20_200, speedAt(20.0) - 10)
        val run = meter.runs.first()

        assertEquals(60.0 / 3.6 / accel, run.split(SplitKind.TO_60)!!.seconds, 0.05)
        assertEquals(100.0 / 3.6 / accel, run.split(SplitKind.TO_100)!!.seconds, 0.05)
        assertEquals(140.0 / 3.6 / accel, run.split(SplitKind.TO_140)!!.seconds, 0.05)
        // 60-100 это разница двух предыдущих
        assertEquals(40.0 / 3.6 / accel, run.split(SplitKind.FROM_60_TO_100)!!.seconds, 0.05)
        assertEquals(40.0 / 3.6 / accel, run.split(SplitKind.FROM_80_TO_120)!!.seconds, 0.05)
        // Пиковое ускорение 3 м/с² это 0.306 g
        assertEquals(0.306, run.peakAccelerationG, 0.02)
    }

    /** Замеры по дистанции: время и скорость на финише. */
    @Test
    fun distanceSplitsMatchPhysics() {
        val meter = PerformanceMeter()
        driveAcceleration(meter, 20.0)
        meter.add(20_200, speedAt(20.0) - 10)
        val run = meter.runs.first()

        val expected402 = sqrt(2 * 402.336 / accel)
        val quarter = run.split(SplitKind.DISTANCE_402)!!
        assertEquals(expected402, quarter.seconds, 0.1)
        assertEquals(accel * expected402 * 3.6, quarter.speedKmh!!, 1.0)

        val expected201 = sqrt(2 * 201.168 / accel)
        val eighth = run.split(SplitKind.DISTANCE_201)!!
        assertEquals(expected201, eighth.seconds, 0.1)

        // Тысячу метров за 20 секунд с таким ускорением пройти не успеваем
        assertNull(run.split(SplitKind.DISTANCE_1000))
        // В путь входит и последний, уже тормозящий шаг опроса - отсюда допуск
        assertEquals(0.5 * accel * 20.0 * 20.0, run.distanceM, 15.0)
    }

    /** Целые км/ч с шины (а не дробные) ухудшают точность, но не ломают замер. */
    @Test
    fun integerSpeedResolutionStillWorks() {
        val meter = PerformanceMeter()
        driveAcceleration(meter, 15.0, stepMillis = 250, round = true)
        meter.add(15_250, speedAt(15.0) - 10)
        val run = meter.runs.first()
        assertEquals(100.0 / 3.6 / accel, run.split(SplitKind.TO_100)!!.seconds, 0.3)
    }

    /** Разрыв в данных бракует заезд, а не дорисовывает его. */
    @Test
    fun dataGapAbortsTheRun() {
        val meter = PerformanceMeter()
        meter.add(0, 0.0)
        meter.add(200, 5.0)
        meter.add(400, 15.0)
        // Пропажа связи на три секунды
        meter.add(3_400, 90.0)
        meter.add(3_600, 95.0)
        meter.add(3_800, 80.0)
        assertTrue("заезд с дырой не должен попасть в результаты", meter.runs.isEmpty())
    }

    /** Торможение со 100 км/ч: путь совпадает с v²/2a. */
    @Test
    fun brakingDistance() {
        val meter = PerformanceMeter()
        val decel = 8.0
        val startSpeed = 100.0 / 3.6
        var t = 0L
        while (true) {
            val v = startSpeed - decel * (t / 1000.0)
            meter.add(t, (v * 3.6).coerceAtLeast(0.0))
            if (v <= 0) break
            t += 100
        }
        val braking = meter.brakings.firstOrNull()
        assertNotNull("торможение должно быть замерено", braking)
        assertEquals(100.0, braking!!.fromKmh, 0.6)
        assertEquals(startSpeed * startSpeed / (2 * decel), braking.distanceM, 2.0)
        assertEquals(startSpeed / decel, braking.seconds, 0.2)
        assertEquals(decel / 9.80665, braking.peakDecelerationG, 0.05)
    }

    /** Оценка мощности: сверка с расчётом по второму закону Ньютона. */
    @Test
    fun powerEstimateMatchesHandCalculation() {
        val profile = VehicleProfiles.UAZ_PATRIOT_2016
        val estimate = DynoEstimator.estimate(profile, speedKmh = 72.0, accelerationMs2 = 2.0, rpm = 3000.0)
        // v = 20 м/с, m = 2225 кг
        // разгон: 2225*2*20 = 89.0 кВт
        // качение: 2225*9.807*0.015*20 = 6.55 кВт
        // воздух: 0.5*1.225*0.5*3.2*8000 = 7.84 кВт
        // итого на колёсах около 103.4 кВт, на двигателе /0.85 = 121.6 кВт = 165 л.с.
        assertEquals(103.4, estimate.wheelPowerKw, 1.0)
        assertEquals(165.4, estimate.enginePowerHp, 2.0)
        // Момент при 3000 об/мин: 121600 Вт / (2π*50) = 387 Н·м
        assertEquals(387.0, estimate.engineTorqueNm!!, 5.0)
    }

    /** Без оборотов момент не считается, но мощность - да. */
    @Test
    fun powerWithoutRpm() {
        val estimate = DynoEstimator.estimate(VehicleProfiles.GENERIC, 50.0, 1.5)
        assertNull(estimate.engineTorqueNm)
        assertTrue(estimate.enginePowerHp > 0)
    }

    /** Пиковая мощность за заезд пишется в результат, если задан профиль. */
    @Test
    fun runRecordsPeakPower() {
        val meter = PerformanceMeter(profile = VehicleProfiles.UAZ_PATRIOT_2016)
        driveAcceleration(meter, 18.0, rpm = 3500.0)
        meter.add(18_200, speedAt(18.0) - 10)
        val run = meter.runs.first()
        assertNotNull(run.peakPowerHp)
        // На равноускоренном разгоне мощность растёт со скоростью, поэтому пик - в конце
        assertTrue("пик на высокой скорости: ${run.peakPowerAtKmh}", run.peakPowerAtKmh!! > 150.0)
        assertTrue("мощность ${run.peakPowerHp}", run.peakPowerHp!! > 100.0)
    }

    /** Трогание и мгновенная остановка не создают мусорный заезд. */
    @Test
    fun crawlingDoesNotCreateRun() {
        val meter = PerformanceMeter()
        meter.add(0, 0.0)
        meter.add(200, 2.0)
        meter.add(400, 3.0)
        meter.add(600, 0.0)
        assertTrue(meter.runs.isEmpty())
    }
}
