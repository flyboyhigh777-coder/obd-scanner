package com.obdscanner.core.perf

import com.obdscanner.core.vehicle.VehicleProfile
import kotlin.math.PI

/** Оценка мощности и момента в один момент времени. */
data class PowerEstimate(
    val speedKmh: Double,
    val accelerationMs2: Double,
    /** Мощность на колёсах, кВт. */
    val wheelPowerKw: Double,
    /** Мощность двигателя с поправкой на потери в трансмиссии, л.с. */
    val enginePowerHp: Double,
    /** Момент двигателя, Н·м - только если известны обороты. */
    val engineTorqueNm: Double?
)

/**
 * Оценка мощности по разгону - то, что в приложениях называют "виртуальный
 * стенд". Работает по второму закону Ньютона: мощность, которую двигатель
 * отдаёт на колёса, уходит на разгон массы и на преодоление сопротивления
 * качению и воздуха.
 *
 *     P = m·a·v + m·g·Crr·v + ½·ρ·Cx·S·v³
 *
 * Это ОЦЕНКА, и вот почему она не заменяет стенд:
 *  - масса, Cx и площадь берутся из профиля, то есть заданы человеком;
 *  - уклон дороги, ветер и температура воздуха не учитываются вовсе, а на
 *    скорости 100 км/ч уклон в 2% даёт ошибку около 10%;
 *  - скорость с шины OBD-II приходит целыми км/ч и с задержкой, так что
 *    ускорение считается грубо;
 *  - потери в трансмиссии взяты одним числом (15% для полного привода с
 *    раздаткой - типовая величина, а не измеренная).
 *
 * Поэтому полезно сравнивать замеры между собой (до и после обслуживания), а не
 * спорить с паспортными 134 л.с.
 */
object DynoEstimator {

    private const val GRAVITY = 9.80665
    private const val AIR_DENSITY = 1.225
    private const val KW_PER_HP = 0.73549875

    /** Типовые потери в трансмиссии рамного внедорожника с раздаточной коробкой. */
    const val DEFAULT_DRIVETRAIN_LOSS = 0.15

    fun estimate(
        profile: VehicleProfile,
        speedKmh: Double,
        accelerationMs2: Double,
        rpm: Double? = null,
        drivetrainLoss: Double = DEFAULT_DRIVETRAIN_LOSS
    ): PowerEstimate {
        val v = speedKmh / 3.6
        val mass = profile.totalMassKg
        val inertia = mass * accelerationMs2 * v
        val rolling = mass * GRAVITY * profile.rollingResistance * v
        val aero = 0.5 * AIR_DENSITY * profile.dragCoefficient * profile.frontalAreaM2 * v * v * v
        val wheelWatts = inertia + rolling + aero
        val engineWatts = wheelWatts / (1.0 - drivetrainLoss.coerceIn(0.0, 0.5))
        val torque = rpm?.takeIf { it > 500 }?.let { engineWatts / (2.0 * PI * it / 60.0) }
        return PowerEstimate(
            speedKmh = speedKmh,
            accelerationMs2 = accelerationMs2,
            wheelPowerKw = wheelWatts / 1000.0,
            enginePowerHp = engineWatts / 1000.0 / KW_PER_HP,
            engineTorqueNm = torque
        )
    }
}
