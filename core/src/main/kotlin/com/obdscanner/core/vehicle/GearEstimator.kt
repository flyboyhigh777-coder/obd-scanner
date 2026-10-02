package com.obdscanner.core.vehicle

import kotlin.math.abs

/**
 * Определение включённой передачи по отношению оборотов к скорости.
 *
 * Передаточные числа коробки и моста нигде в стандарте OBD-II не передаются, а
 * вписывать их в профиль - значит ошибиться: у одной модели бывает несколько
 * коробок и несколько главных пар. Поэтому передачи не задаются, а
 * ВЫУЧИВАЮТСЯ: на каждой передаче отношение об/мин к км/ч постоянно, так что
 * достаточно собрать эти отношения и сгруппировать их. Группы, отсортированные
 * по убыванию отношения, и есть передачи, начиная с первой.
 *
 * Пробуксовка сцепления, нейтраль и переключения дают разброс, поэтому выборки
 * берутся только при устойчивом движении, а группа считается передачей лишь
 * после [minSamplesPerGear] попаданий.
 */
class GearEstimator(
    private val minSpeedKmh: Double = 15.0,
    private val minRpm: Double = 900.0,
    /** Относительный допуск, внутри которого отношения считаются одной передачей. */
    private val tolerance: Double = 0.06,
    private val minSamplesPerGear: Int = 8
) {

    private class Cluster(var ratio: Double, var count: Int)

    private val clusters = ArrayList<Cluster>()

    /** Сколько передач уже распознано. */
    val learnedGears: Int get() = clusters.count { it.count >= minSamplesPerGear }

    /** Выученные отношения об/мин к км/ч, от первой передачи к высшей. */
    fun ratios(): List<Double> =
        clusters.filter { it.count >= minSamplesPerGear }.map { it.ratio }.sortedDescending()

    /**
     * Добавляет замер. Возвращает номер передачи (с 1) или null, если по этим
     * данным сказать нечего.
     */
    fun add(rpm: Double, speedKmh: Double): Int? {
        if (speedKmh < minSpeedKmh || rpm < minRpm) return null
        val ratio = rpm / speedKmh
        val existing = clusters.minByOrNull { abs(it.ratio - ratio) / it.ratio }
        if (existing != null && abs(existing.ratio - ratio) / existing.ratio <= tolerance) {
            // Скользящее среднее: центр группы уточняется, но не прыгает от одного замера.
            existing.ratio += (ratio - existing.ratio) / (existing.count + 1).coerceAtMost(20)
            existing.count++
        } else {
            clusters.add(Cluster(ratio, 1))
        }
        return gearFor(rpm, speedKmh)
    }

    /** Номер передачи для готовых значений, без запоминания. */
    fun gearFor(rpm: Double, speedKmh: Double): Int? {
        if (speedKmh < minSpeedKmh || rpm < minRpm) return null
        val known = ratios()
        if (known.isEmpty()) return null
        val ratio = rpm / speedKmh
        val index = known.indices.minByOrNull { abs(known[it] - ratio) } ?: return null
        // Если отношение далеко от всех выученных - это переключение или
        // пробуксовка, и честнее не показывать ничего.
        if (abs(known[index] - ratio) / known[index] > tolerance * 2) return null
        return index + 1
    }

    fun reset() = clusters.clear()
}
