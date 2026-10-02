package com.obdscanner.core

import com.obdscanner.core.vehicle.GearEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Передачи не задаются числами, а выучиваются по отношению оборотов к скорости -
 * проверяем это на синтетической пятиступенчатой коробке.
 */
class GearEstimatorTest {

    /** Отношение об/мин к км/ч для каждой передачи - типовое для внедорожника. */
    private val ratios = listOf(95.0, 55.0, 37.0, 27.0, 21.0)

    private fun teach(estimator: GearEstimator, samples: Int = 12) {
        for (gear in ratios.indices) {
            repeat(samples) { step ->
                val speed = 20.0 + gear * 25.0 + step
                estimator.add(ratios[gear] * speed, speed)
            }
        }
    }

    /** После обучения распознаны все пять передач, по убыванию отношения. */
    @Test
    fun learnsAllGears() {
        val estimator = GearEstimator()
        teach(estimator)
        assertEquals(5, estimator.learnedGears)
        val learned = estimator.ratios()
        assertEquals(5, learned.size)
        for (i in ratios.indices) {
            assertEquals("передача ${i + 1}", ratios[i], learned[i], ratios[i] * 0.05)
        }
    }

    /** Номер передачи определяется по текущим оборотам и скорости. */
    @Test
    fun detectsCurrentGear()  {
        val estimator = GearEstimator()
        teach(estimator)
        assertEquals(1, estimator.gearFor(ratios[0] * 25.0, 25.0))
        assertEquals(3, estimator.gearFor(ratios[2] * 60.0, 60.0))
        assertEquals(5, estimator.gearFor(ratios[4] * 110.0, 110.0))
    }

    /** На малой скорости и на холостых передача не определяется. */
    @Test
    fun noGearAtStandstill() {
        val estimator = GearEstimator()
        teach(estimator)
        assertNull(estimator.gearFor(800.0, 0.0))
        assertNull(estimator.gearFor(2000.0, 5.0))
    }

    /** Пробуксовка или переключение: отношение далеко от выученных - честный null. */
    @Test
    fun slipGivesNoGear() {
        val estimator = GearEstimator()
        teach(estimator)
        // 4000 об/мин на 30 км/ч - это отношение 133, выше первой передачи
        assertNull(estimator.gearFor(4000.0, 30.0))
    }

    /** До обучения ничего не выдумывается. */
    @Test
    fun nothingBeforeLearning() {
        val estimator = GearEstimator()
        assertEquals(0, estimator.learnedGears)
        assertNull(estimator.gearFor(2000.0, 50.0))
        // Пары замеров мало, чтобы объявить передачу
        repeat(3) { estimator.add(37.0 * 60.0, 60.0) }
        assertEquals(0, estimator.learnedGears)
    }

    /** Сброс забывает всё - нужен при смене машины. */
    @Test
    fun resetForgets() {
        val estimator = GearEstimator()
        teach(estimator)
        estimator.reset()
        assertEquals(0, estimator.learnedGears)
        assertTrue(estimator.ratios().isEmpty())
    }
}
