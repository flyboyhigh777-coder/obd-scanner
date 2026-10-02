package com.obdscanner.core.trip

import com.obdscanner.core.obd.FuelEconomy
import com.obdscanner.core.obd.ObdPid
import com.obdscanner.core.obd.Pids
import java.util.Locale
import kotlin.math.max

/** Один замер: когда и что было прочитано. Ключ - номер PID. */
data class TripSample(val timeMillis: Long, val values: Map<Int, Double>)

/** Итоги поездки. */
data class TripStats(
    val durationMillis: Long,
    val distanceKm: Double,
    val maxSpeedKmh: Double,
    /** Средняя скорость в движении - без времени на светофорах. */
    val averageMovingSpeedKmh: Double,
    val idleMillis: Long,
    val maxRpm: Double,
    val maxCoolantC: Double?,
    val fuelLitres: Double,
    val consumptionL100Km: Double?,
    val sampleCount: Int
)

/**
 * Запись поездки: складывает замеры и считает итоги.
 *
 * Пробег и расход получаются интегрированием по времени между замерами
 * (метод трапеций): GPS здесь не нужен - скорость и расход воздуха приходят от
 * самой машины. Точность зависит от частоты опроса: на 200-300 мс между
 * замерами расхождение с одометром - проценты, а не разы.
 *
 * Длинные промежутки между замерами (приложение было свёрнуто, связь с
 * адаптером рвалась) в интегрирование не идут: за 30 секунд без данных машина
 * могла и стоять, и ехать, и дорисовывать такой участок по последнему
 * известному значению - значит врать.
 */
class TripRecorder(private val maxGapMillis: Long = 5_000) {

    private val samples = ArrayList<TripSample>()

    val size: Int get() = samples.size

    val first: TripSample? get() = samples.firstOrNull()
    val last: TripSample? get() = samples.lastOrNull()

    fun add(sample: TripSample) {
        samples.add(sample)
    }

    fun add(timeMillis: Long, values: Map<Int, Double>) = add(TripSample(timeMillis, values))

    fun clear() = samples.clear()

    fun snapshot(): List<TripSample> = samples.toList()

    fun stats(dieselFuel: Boolean = false): TripStats {
        if (samples.isEmpty()) {
            return TripStats(0, 0.0, 0.0, 0.0, 0, 0.0, null, 0.0, null, 0)
        }
        var distanceKm = 0.0
        var litres = 0.0
        var movingMillis = 0L
        var idleMillis = 0L
        var maxSpeed = 0.0
        var maxRpm = 0.0
        var maxCoolant: Double? = null

        fun speedOf(s: TripSample) = s.values[Pids.SPEED.id]
        fun rateOf(s: TripSample): Double? {
            s.values[Pids.FUEL_RATE.id]?.let { return it }
            val maf = s.values[Pids.MAF.id] ?: return null
            return FuelEconomy.litresPerHourFromMaf(maf, dieselFuel)
        }

        for (s in samples) {
            speedOf(s)?.let { maxSpeed = max(maxSpeed, it) }
            s.values[Pids.RPM.id]?.let { maxRpm = max(maxRpm, it) }
            s.values[Pids.COOLANT_TEMP.id]?.let { maxCoolant = max(maxCoolant ?: it, it) }
        }

        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            val dtMillis = b.timeMillis - a.timeMillis
            if (dtMillis <= 0 || dtMillis > maxGapMillis) continue
            val hours = dtMillis / 3_600_000.0

            val va = speedOf(a); val vb = speedOf(b)
            if (va != null && vb != null) {
                distanceKm += (va + vb) / 2.0 * hours
                if ((va + vb) / 2.0 >= 1.0) movingMillis += dtMillis else idleMillis += dtMillis
            }
            val ra = rateOf(a); val rb = rateOf(b)
            if (ra != null && rb != null) litres += (ra + rb) / 2.0 * hours
        }

        val duration = samples.last().timeMillis - samples.first().timeMillis
        val averageMoving = if (movingMillis > 0) distanceKm / (movingMillis / 3_600_000.0) else 0.0
        val consumption = if (distanceKm >= 0.1 && litres > 0) litres / distanceKm * 100.0 else null
        return TripStats(
            durationMillis = duration,
            distanceKm = distanceKm,
            maxSpeedKmh = maxSpeed,
            averageMovingSpeedKmh = averageMoving,
            idleMillis = idleMillis,
            maxRpm = maxRpm,
            maxCoolantC = maxCoolant,
            fuelLitres = litres,
            consumptionL100Km = consumption,
            sampleCount = samples.size
        )
    }

    /**
     * CSV для разбора на компьютере. Разделитель - точка с запятой, десятичный
     * разделитель - точка: именно такую пару Excel с русской локалью открывает
     * без "мастера импорта", а pandas и LibreOffice читают как есть.
     */
    fun toCsv(pids: List<ObdPid> = Pids.ALL, startMillis: Long? = null): String {
        val columns = pids.filter { pid -> samples.any { pid.id in it.values } }
        val text = StringBuilder()
        text.append("time_s")
        for (pid in columns) text.append(';').append(pid.en.replace(';', ',')).append(" [").append(pid.unit.en).append(']')
        text.append('\n')
        val zero = startMillis ?: samples.firstOrNull()?.timeMillis ?: 0L
        for (s in samples) {
            text.append("%.2f".format(Locale.ROOT, (s.timeMillis - zero) / 1000.0))
            for (pid in columns) {
                text.append(';')
                s.values[pid.id]?.let { text.append("%.${pid.decimals}f".format(Locale.ROOT, it)) }
            }
            text.append('\n')
        }
        return text.toString()
    }
}
