package com.obdscanner.core.perf

import com.obdscanner.core.elm.ObdText
import com.obdscanner.core.vehicle.VehicleProfile
import kotlin.math.abs
import kotlin.math.max

/** Что именно замеряется. */
enum class SplitKind(val ru: String, val en: String) {
    TO_60("0-60 км/ч", "0-60 km/h"),
    TO_100("0-100 км/ч", "0-100 km/h"),
    TO_140("0-140 км/ч", "0-140 km/h"),
    FROM_60_TO_100("60-100 км/ч", "60-100 km/h"),
    FROM_80_TO_120("80-120 км/ч", "80-120 km/h"),
    DISTANCE_201("201 м (1/8 мили)", "201 m (1/8 mile)"),
    DISTANCE_402("402 м (1/4 мили)", "402 m (1/4 mile)"),
    DISTANCE_1000("1000 м", "1000 m");

    val title: String get() = ObdText.pick(ru, en)
}

/** Результат одного замера внутри заезда. */
data class Split(
    val kind: SplitKind,
    val seconds: Double,
    /** Скорость на финише - только для замеров по дистанции. */
    val speedKmh: Double? = null
)

/** Заезд на разгон. */
data class AccelerationRun(
    val startedAtMillis: Long,
    val durationS: Double,
    val distanceM: Double,
    val maxSpeedKmh: Double,
    val peakAccelerationG: Double,
    val splits: List<Split>,
    /** Наибольшая оценка мощности за заезд, л.с. - если был задан профиль машины. */
    val peakPowerHp: Double? = null,
    val peakPowerAtKmh: Double? = null
) {
    fun split(kind: SplitKind): Split? = splits.firstOrNull { it.kind == kind }
}

/** Торможение: со скорости [fromKmh] до полной остановки. */
data class BrakingRun(
    val startedAtMillis: Long,
    val fromKmh: Double,
    val seconds: Double,
    val distanceM: Double,
    val peakDecelerationG: Double
)

/**
 * Замеры разгона и торможения по данным шины.
 *
 * Как это работает: скармливаем ему пары (время, скорость) с опроса, он сам
 * видит начало разгона (машина тронулась с места), считает дистанцию
 * интегрированием скорости и ловит моменты, когда пройдены контрольные скорости
 * и дистанции. Промежуточные значения берутся линейной интерполяцией между
 * замерами - без неё при опросе 3-5 раз в секунду время "0-100" гуляло бы на
 * четверть секунды.
 *
 * Чего ждать от точности: скорость по OBD-II приходит целыми км/ч, с задержкой
 * в десятки миллисекунд и не чаще нескольких раз в секунду. Этого достаточно,
 * чтобы сравнивать свои заезды между собой, но не для спора с заводскими
 * характеристиками - в отличие от замера по GPS с частотой 10 Гц, не говоря уже
 * о мерной полосе.
 */
class PerformanceMeter(
    private val profile: VehicleProfile? = null,
    /** Скорость, ниже которой машина считается стоящей, км/ч. */
    private val standstillKmh: Double = 1.5,
    /** Насколько скорость должна упасть от максимума, чтобы заезд считался законченным. */
    private val finishDropKmh: Double = 6.0,
    /** Разрыв в данных, после которого заезд бракуется, мс. */
    private val maxGapMillis: Long = 1500
) {

    private data class Sample(val timeMillis: Long, val speedKmh: Double)

    private var previous: Sample? = null
    private var standing = false

    // --- текущий разгон
    private var runStart: Sample? = null
    /**
     * Момент старта, с, доведённый интерполяцией до скорости 0: иначе отсчёт
     * начинался бы с замера, на котором машина уже катится, и все времена
     * оказывались бы занижены на долю секунды - тем сильнее, чем реже опрос.
     */
    private var runStartSeconds: Double = 0.0
    private var runDistance = 0.0
    private var runMaxSpeed = 0.0
    private var runPeakAccel = 0.0
    private var runPeakPowerHp: Double? = null
    private var runPeakPowerSpeed: Double? = null
    private val runSplits = ArrayList<Split>()
    private val speedTargets = listOf(60.0 to SplitKind.TO_60, 100.0 to SplitKind.TO_100, 140.0 to SplitKind.TO_140)
    private val distanceTargets = listOf(
        201.168 to SplitKind.DISTANCE_201,
        402.336 to SplitKind.DISTANCE_402,
        1000.0 to SplitKind.DISTANCE_1000
    )
    private var timeAt60: Double? = null
    private var timeAt80: Double? = null
    private var timeAt120: Double? = null

    // --- текущее торможение
    private var brakeStart: Sample? = null
    private var brakeDistance = 0.0
    private var brakePeakDecel = 0.0

    /** Завершённые заезды, новые в начале списка. */
    val runs = ArrayList<AccelerationRun>()

    /** Завершённые торможения, новые в начале списка. */
    val brakings = ArrayList<BrakingRun>()

    /** Идёт ли замер прямо сейчас - приложение показывает это надписью. */
    val measuring: Boolean get() = runStart != null

    /** Текущая оценка ускорения, g - для "гэ-метра" на экране. */
    var currentG: Double = 0.0
        private set

    /** Текущая оценка мощности, л.с. */
    var currentPowerHp: Double? = null
        private set

    fun reset() {
        previous = null; standing = false
        abortRun(); abortBraking()
        runs.clear(); brakings.clear()
        currentG = 0.0; currentPowerHp = null
    }

    /**
     * Добавляет замер. Возвращает заезд, если он только что завершился.
     *
     * @param rpm нужны только для оценки момента двигателя, можно не передавать.
     */
    fun add(timeMillis: Long, speedKmh: Double, rpm: Double? = null): AccelerationRun? {
        val prev = previous
        val sample = Sample(timeMillis, speedKmh)
        if (prev == null) {
            previous = sample
            standing = speedKmh <= standstillKmh
            return null
        }
        val dtSeconds = (timeMillis - prev.timeMillis) / 1000.0
        if (dtSeconds <= 0) return null
        if (timeMillis - prev.timeMillis > maxGapMillis) {
            // Пропала связь или приложение было свёрнуто: дорисовывать участок
            // по последнему известному значению - значит соврать в замере.
            previous = sample
            abortRun(); abortBraking()
            standing = speedKmh <= standstillKmh
            return null
        }

        val averageSpeedMs = (prev.speedKmh + speedKmh) / 2.0 / 3.6
        val stepDistance = averageSpeedMs * dtSeconds
        val accelerationMs2 = (speedKmh - prev.speedKmh) / 3.6 / dtSeconds
        currentG = accelerationMs2 / GRAVITY
        currentPowerHp = profile?.let {
            if (speedKmh > 5 && accelerationMs2 > 0) {
                DynoEstimator.estimate(it, speedKmh, accelerationMs2, rpm).enginePowerHp
            } else {
                null
            }
        }

        var finished: AccelerationRun? = null

        // --- разгон
        if (runStart == null) {
            if (standing && speedKmh > standstillKmh) startRun(prev, sample, dtSeconds)
        }
        if (runStart != null) {
            runDistance += stepDistance
            runMaxSpeed = max(runMaxSpeed, speedKmh)
            runPeakAccel = max(runPeakAccel, accelerationMs2)
            currentPowerHp?.let { power ->
                if (power > (runPeakPowerHp ?: 0.0)) {
                    runPeakPowerHp = power; runPeakPowerSpeed = speedKmh
                }
            }
            checkSpeedTargets(prev, sample, dtSeconds)
            checkDistanceTargets(prev, sample, dtSeconds, runDistance - stepDistance)
            if (speedKmh <= standstillKmh || runMaxSpeed - speedKmh >= finishDropKmh) {
                finished = finishRun(timeMillis)
            }
        }

        // --- торможение
        if (brakeStart == null) {
            if (prev.speedKmh >= BRAKING_FROM_KMH && speedKmh < prev.speedKmh) {
                brakeStart = prev; brakeDistance = 0.0; brakePeakDecel = 0.0
            }
        }
        if (brakeStart != null) {
            brakeDistance += stepDistance
            brakePeakDecel = max(brakePeakDecel, -accelerationMs2)
            val start = brakeStart!!
            when {
                speedKmh <= standstillKmh -> {
                    brakings.add(
                        0,
                        BrakingRun(
                            startedAtMillis = start.timeMillis,
                            fromKmh = start.speedKmh,
                            seconds = (timeMillis - start.timeMillis) / 1000.0,
                            distanceM = brakeDistance,
                            peakDecelerationG = brakePeakDecel / GRAVITY
                        )
                    )
                    abortBraking()
                }
                // Снова разгоняется - это было не торможение до остановки.
                speedKmh > start.speedKmh -> abortBraking()
            }
        }

        standing = speedKmh <= standstillKmh
        previous = sample
        return finished
    }

    private fun startRun(from: Sample, now: Sample, dtSeconds: Double) {
        runStart = from
        runStartSeconds = launchTime(from, now, dtSeconds)
        runDistance = 0.0
        runMaxSpeed = from.speedKmh
        runPeakAccel = 0.0
        runPeakPowerHp = null
        runPeakPowerSpeed = null
        runSplits.clear()
        timeAt60 = null; timeAt80 = null; timeAt120 = null
    }

    private fun abortRun() {
        runStart = null
        runSplits.clear()
    }

    private fun abortBraking() {
        brakeStart = null
        brakeDistance = 0.0
        brakePeakDecel = 0.0
    }

    private fun finishRun(timeMillis: Long): AccelerationRun? {
        val start = runStart ?: return null
        val run = AccelerationRun(
            startedAtMillis = start.timeMillis,
            durationS = (timeMillis - start.timeMillis) / 1000.0,
            distanceM = runDistance,
            maxSpeedKmh = runMaxSpeed,
            peakAccelerationG = runPeakAccel / GRAVITY,
            splits = runSplits.toList(),
            peakPowerHp = runPeakPowerHp,
            peakPowerAtKmh = runPeakPowerSpeed
        )
        abortRun()
        // Заезд без единого контрольного замера (тронулся и сразу встал) не нужен.
        return if (run.splits.isEmpty() && run.distanceM < 20) null else run.also { runs.add(0, it) }
    }

    /** Время от начала заезда до момента, когда скорость прошла [target]. */
    private fun checkSpeedTargets(prev: Sample, now: Sample, dtSeconds: Double) {
        if (runStart == null) return
        for ((target, kind) in speedTargets) {
            if (prev.speedKmh < target && now.speedKmh >= target && runSplits.none { it.kind == kind }) {
                val seconds = interpolatedTime(prev, now, dtSeconds, target) - runStartSeconds
                runSplits.add(Split(kind, seconds))
                if (kind == SplitKind.TO_60) timeAt60 = seconds
                if (kind == SplitKind.TO_100) {
                    timeAt60?.let { runSplits.add(Split(SplitKind.FROM_60_TO_100, seconds - it)) }
                }
            }
        }
        // 80-120 считается отдельно: это не "от старта", а участок.
        if (prev.speedKmh < 80.0 && now.speedKmh >= 80.0 && timeAt80 == null) {
            timeAt80 = interpolatedTime(prev, now, dtSeconds, 80.0)
        }
        if (prev.speedKmh < 120.0 && now.speedKmh >= 120.0 && timeAt120 == null) {
            timeAt120 = interpolatedTime(prev, now, dtSeconds, 120.0)
            val from = timeAt80
            if (from != null && runSplits.none { it.kind == SplitKind.FROM_80_TO_120 }) {
                runSplits.add(Split(SplitKind.FROM_80_TO_120, timeAt120!! - from))
            }
        }
    }

    private fun checkDistanceTargets(prev: Sample, now: Sample, dtSeconds: Double, distanceBefore: Double) {
        if (runStart == null) return
        for ((target, kind) in distanceTargets) {
            if (distanceBefore < target && runDistance >= target && runSplits.none { it.kind == kind }) {
                val part = ((target - distanceBefore) / (runDistance - distanceBefore)).coerceIn(0.0, 1.0)
                val seconds = (prev.timeMillis / 1000.0 + part * dtSeconds) - runStartSeconds
                val trap = prev.speedKmh + (now.speedKmh - prev.speedKmh) * part
                runSplits.add(Split(kind, seconds, trap))
            }
        }
    }

    /**
     * Момент начала движения, с. Машина тронулась ГДЕ-ТО между последним
     * стоячим замером и первым катящимся, поэтому время нулевой скорости
     * получается экстраполяцией назад от двух первых катящихся замеров. Без
     * этого отсчёт начинался бы с замера, на котором скорость уже не ноль, и
     * все времена разгона выходили бы меньше настоящих на шаг опроса - при
     * опросе 3 раза в секунду это треть секунды на "сотне".
     */
    private fun launchTime(prev: Sample, now: Sample, dtSeconds: Double): Double {
        val span = now.speedKmh - prev.speedKmh
        if (span <= 1e-9) return prev.timeMillis / 1000.0
        // Отношение отрицательное: ноль был раньше prev. Назад уходим не больше
        // чем на один шаг опроса - дальше это уже гадание.
        val part = ((0.0 - prev.speedKmh) / span).coerceIn(-1.0, 0.0)
        return prev.timeMillis / 1000.0 + part * dtSeconds
    }

    /** Абсолютное время (с), когда скорость прошла [target], линейно между замерами. */
    private fun interpolatedTime(prev: Sample, now: Sample, dtSeconds: Double, target: Double): Double {
        val span = now.speedKmh - prev.speedKmh
        val part = if (abs(span) < 1e-9) 0.0 else ((target - prev.speedKmh) / span).coerceIn(0.0, 1.0)
        return prev.timeMillis / 1000.0 + part * dtSeconds
    }

    private companion object {
        const val GRAVITY = 9.80665

        /** С какой скорости начинает считаться замер торможения. */
        const val BRAKING_FROM_KMH = 90.0
    }
}
