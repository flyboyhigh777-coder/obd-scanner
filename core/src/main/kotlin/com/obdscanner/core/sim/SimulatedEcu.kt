package com.obdscanner.core.sim

import com.obdscanner.core.dtc.DtcCodec
import com.obdscanner.core.obd.PidSupport
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.session.ObdChannel
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Модель движения: что показывали бы приборы, если бы машина ехала по
 * повторяющемуся циклу - холостой ход, разгон, трасса, торможение. Всё считается
 * от времени, без случайных чисел, поэтому тесты повторяемы, а демо-режим
 * выглядит живым.
 *
 * Цикл 90 секунд; обороты считаются из скорости и номера передачи, а не задаются
 * отдельно - иначе на экране сразу видно, что данные не связаны между собой.
 */
class DrivingModel {

    /** Передаточные числа: обороты = скорость * коэффициент передачи. */
    private val gearRatios = doubleArrayOf(90.0, 52.0, 36.0, 27.0, 22.0, 18.0)

    private fun gearFor(speed: Double): Int = when {
        speed < 20 -> 0
        speed < 35 -> 1
        speed < 55 -> 2
        speed < 80 -> 3
        speed < 110 -> 4
        else -> 5
    }

    fun speed(t: Double): Double {
        val phase = t % 90.0
        return when {
            phase < 12 -> 0.0
            phase < 30 -> (phase - 12) / 18.0 * 95.0           // разгон до 95 км/ч
            phase < 60 -> 95.0 + 8.0 * sin((phase - 30) / 4.0) // трасса, газ "гуляет"
            phase < 75 -> max(0.0, 95.0 * (1 - (phase - 60) / 15.0))
            else -> 0.0
        }
    }

    fun rpm(t: Double): Double {
        val v = speed(t)
        if (v < 1.0) {
            // Холостой ход плавает, как на живом двигателе.
            return 790.0 + 25.0 * sin(t / 1.7)
        }
        return (v * gearRatios[gearFor(v)]).coerceIn(900.0, 6500.0)
    }

    fun throttle(t: Double): Double {
        val phase = t % 90.0
        val v = speed(t)
        return when {
            v < 1.0 -> 13.5
            phase < 30 -> 55.0 + 10.0 * sin(phase / 2.0)   // разгон - педаль нажата
            phase < 60 -> 22.0 + 6.0 * sin((phase - 30) / 3.0)
            else -> 11.0                                    // торможение двигателем
        }.coerceIn(0.0, 100.0)
    }

    fun load(t: Double): Double {
        val throttlePart = throttle(t) * 0.8
        val rpmPart = rpm(t) / 6500.0 * 25.0
        return (throttlePart + rpmPart).coerceIn(12.0, 97.0)
    }

    /** Прогрев: 20 -> 90 за первые две минуты, дальше термостат и вентилятор. */
    fun coolant(t: Double): Double =
        if (t < 150) 20.0 + 70.0 * (1 - exp(-t / 55.0)) else 89.0 + 3.0 * sin(t / 30.0)

    fun maf(t: Double): Double {
        val displacementFactor = 0.00034 // л/такт * плотность, подогнано под 1.6 л
        return (rpm(t) * load(t) * displacementFactor).coerceIn(1.8, 160.0)
    }

    fun map(t: Double): Double = (28.0 + load(t) * 0.68).coerceIn(25.0, 101.0)

    fun timingAdvance(t: Double): Double = (8.0 + rpm(t) / 200.0 - load(t) / 8.0).coerceIn(-5.0, 42.0)

    fun voltage(t: Double): Double = if (rpm(t) > 400) 14.1 - load(t) / 180.0 else 12.3

    /** Лямбда-зонд "звенит" около стехиометрии - это нормальная работа. */
    fun o2Voltage(t: Double): Double = 0.45 + 0.37 * sin(t * 1.9)

    fun shortTrim(t: Double): Double = 2.3 * sin(t / 2.3)

    fun fuelLevel(t: Double): Double = max(8.0, 62.0 - t / 900.0)

    fun intakeTemp(t: Double): Double = 24.0 + min(14.0, load(t) / 7.0)

    fun catalystTemp(t: Double): Double = (320.0 + load(t) * 4.2 + rpm(t) / 28.0).coerceIn(180.0, 900.0)

    fun oilTemp(t: Double): Double = coolant(t) + 6.0 - 2.0 * sin(t / 40.0)
}

/**
 * Симулятор адаптера ELM327 вместе с машиной за ним: отвечает на AT-команды и на
 * запросы OBD-II так же, как настоящий адаптер с выключенными эхом, пробелами и
 * заголовками (ATE0 ATS0 ATH0), включая многокадровые ответы с нумерацией строк.
 *
 * Нужен дважды: это демо-режим приложения (посмотреть все экраны без машины и
 * адаптера) и это же - "железо" под тестами протокола в :core.
 */
class SimulatedEcu(
    val vin: String = "XTA210990G1234567",
    val storedDtcs: MutableList<String> = mutableListOf("P0301", "P0171", "P0420"),
    val pendingDtcs: MutableList<String> = mutableListOf("P0133"),
    val permanentDtcs: MutableList<String> = mutableListOf("P0420"),
    val ecuName: String = "ECM-EngineControl",
    val calibrationId: String = "SIM0001-OBD",
    val milOn: Boolean = true,
    /** Время в миллисекундах - подменяется в тестах, чтобы значения были заданными. */
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ObdChannel {

    private val model = DrivingModel()
    private val start = clock()
    private val pending = StringBuilder()
    private var closed = false

    /** Сколько запросов уже обработано - тесты проверяют, что лишних нет. */
    var requestCount: Int = 0
        private set

    /** Какие PID "умеет" эта виртуальная машина. */
    val supported: Set<Int> = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
        0x13, 0x14, 0x15, 0x1C, 0x1F, 0x20,
        0x21, 0x2F, 0x30, 0x31, 0x33, 0x3C, 0x40,
        0x42, 0x43, 0x44, 0x45, 0x46, 0x49, 0x4C, 0x4D, 0x4E, 0x51, 0x5C, 0x5E
    )

    private fun seconds(): Double = (clock() - start) / 1000.0

    override fun write(bytes: ByteArray) {
        if (closed) return
        val command = String(bytes, Charsets.US_ASCII).trim().uppercase()
        if (command.isEmpty()) return
        requestCount++
        pending.append(respond(command))
    }

    override fun read(dst: ByteArray): Int {
        if (pending.isEmpty()) return 0
        val n = min(dst.size, pending.length)
        for (i in 0 until n) dst[i] = pending[i].code.toByte()
        pending.delete(0, n)
        return n
    }

    override fun close() {
        closed = true
        pending.setLength(0)
    }

    // --- ответы -------------------------------------------------------------

    private fun prompt(body: String) = body + "\r\r>"

    private fun ok() = prompt("OK\r")

    /**
     * Одна строка данных, если нагрузка влезает в кадр CAN (7 байт), иначе -
     * многокадровый ответ: строка с общей длиной и строки с номерами, ровно как
     * печатает ELM327 с выключенными заголовками.
     */
    private fun frame(data: IntArray): String {
        val hex = data.joinToString("") { "%02X".format(it) }
        if (data.size <= 7) return prompt("$hex\r")
        val text = StringBuilder("%03X\r".format(data.size))
        var index = 0
        var line = 0
        while (index < data.size) {
            // В первом кадре CAN два байта занимает служебный заголовок ISO-TP,
            // поэтому данных в нём 6, а в продолжениях - 7.
            val take = if (line == 0) 6 else 7
            val chunk = data.copyOfRange(index, min(index + take, data.size))
            text.append("%X:".format(line))
            text.append(chunk.joinToString("") { "%02X".format(it) })
            text.append('\r')
            index += chunk.size
            line++
        }
        return prompt(text.toString())
    }

    private fun noData() = prompt("NO DATA\r")

    private fun respond(command: String): String {
        if (command.startsWith("AT")) return respondAt(command)
        val service = command.substring(0, min(2, command.length)).toIntOrNull(16) ?: return prompt("?\r")
        return when (service) {
            0x01 -> respondCurrentData(command)
            0x02 -> respondFreezeFrame(command)
            0x03 -> dtcFrame(0x43, storedDtcs)
            0x04 -> { storedDtcs.clear(); pendingDtcs.clear(); frame(intArrayOf(0x44)) }
            0x07 -> dtcFrame(0x47, pendingDtcs)
            0x09 -> respondVehicleInfo(command)
            0x0A -> dtcFrame(0x4A, permanentDtcs)
            else -> noData()
        }
    }

    private fun respondAt(command: String): String = when {
        command == "ATZ" || command == "ATI" -> prompt("ELM327 v1.5 (SIM)\r")
        command == "ATRV" -> prompt("%.1fV\r".format(Locale.ROOT, model.voltage(seconds())))
        command == "ATDPN" -> prompt("6\r")
        command.startsWith("ATDP") -> prompt("ISO 15765-4 (CAN 11/500)\r")
        else -> ok()
    }

    private fun respondCurrentData(command: String): String {
        val pid = command.drop(2).take(2).toIntOrNull(16) ?: return prompt("?\r")
        if (pid in PidSupport.RANGE_PIDS) {
            // Карту диапазона машина отдаёт только если этот диапазон вообще есть:
            // признак - сам PID карты в списке поддерживаемых (0x20, 0x40, ...).
            if (pid != 0x00 && pid !in supported) return noData()
            return frame(intArrayOf(0x41, pid) + PidSupport.encode(pid, supported))
        }
        if (pid !in supported) return noData()
        val data = encodeValue(pid, seconds()) ?: return noData()
        return frame(intArrayOf(0x41, pid) + data)
    }

    /** Стоп-кадр: те же данные, но "замороженные" на 42-й секунде цикла. */
    private fun respondFreezeFrame(command: String): String {
        val pid = command.drop(2).take(2).toIntOrNull(16) ?: return prompt("?\r")
        if (storedDtcs.isEmpty()) return noData()
        val data = encodeValue(pid, 42.0) ?: return noData()
        return frame(intArrayOf(0x42, pid, 0x00) + data)
    }

    private fun respondVehicleInfo(command: String): String {
        return when (command.drop(2).take(2).toIntOrNull(16)) {
            0x00 -> frame(intArrayOf(0x49, 0x00, 0x55, 0x40, 0x00, 0x00))
            0x02 -> frame(intArrayOf(0x49, 0x02, 0x01) + vin.map { it.code }.toIntArray())
            0x04 -> frame(intArrayOf(0x49, 0x04, 0x01) + calibrationId.map { it.code }.toIntArray())
            0x0A -> frame(intArrayOf(0x49, 0x0A, 0x01) + ecuName.map { it.code }.toIntArray())
            else -> noData()
        }
    }

    private fun dtcFrame(answer: Int, codes: List<String>): String {
        val body = ArrayList<Int>()
        body.add(answer)
        body.add(codes.size)
        for (code in codes) {
            val pair = DtcCodec.encode(code) ?: continue
            body.add(pair.first); body.add(pair.second)
        }
        return frame(body.toIntArray())
    }

    /** Значение PID, упакованное в байты ровно так, как это делает ЭБУ. */
    private fun encodeValue(pid: Int, t: Double): IntArray? {
        fun two(value: Int) = intArrayOf((value shr 8) and 0xFF, value and 0xFF)
        return when (pid) {
            Pids.MONITOR_STATUS.id -> {
                val count = storedDtcs.size
                val a = (if (milOn && count > 0) 0x80 else 0x00) or count
                // Поддерживаются пропуски/топливо/компоненты, не завершён тест
                // катализатора - как на машине, которую недавно сбрасывали.
                intArrayOf(a, 0x07, 0xE5, 0x01)
            }
            Pids.FUEL_SYSTEM_STATUS.id -> intArrayOf(0x02, 0x00) // замкнутый контур
            Pids.ENGINE_LOAD.id -> intArrayOf((model.load(t) * 255 / 100).toInt())
            Pids.COOLANT_TEMP.id -> intArrayOf((model.coolant(t) + 40).toInt())
            Pids.SHORT_FUEL_TRIM_1.id -> intArrayOf(((model.shortTrim(t) + 100) * 1.28).toInt())
            Pids.LONG_FUEL_TRIM_1.id -> intArrayOf(((1.6 + 100) * 1.28).toInt())
            Pids.INTAKE_MAP.id -> intArrayOf(model.map(t).toInt())
            Pids.RPM.id -> two((model.rpm(t) * 4).toInt())
            Pids.SPEED.id -> intArrayOf(model.speed(t).toInt())
            Pids.TIMING_ADVANCE.id -> intArrayOf(((model.timingAdvance(t) + 64) * 2).toInt())
            Pids.INTAKE_TEMP.id -> intArrayOf((model.intakeTemp(t) + 40).toInt())
            Pids.MAF.id -> two((model.maf(t) * 100).toInt())
            Pids.THROTTLE.id -> intArrayOf((model.throttle(t) * 255 / 100).toInt())
            0x13 -> intArrayOf(0x03) // есть лямбда-зонды 1 и 2 банка 1
            Pids.O2_B1S1_VOLTAGE.id -> intArrayOf((model.o2Voltage(t) * 200).toInt(), 0x80)
            Pids.O2_B1S2_VOLTAGE.id -> intArrayOf((0.62 * 200).toInt(), 0x80)
            Pids.OBD_STANDARD.id -> intArrayOf(0x06) // EOBD
            Pids.RUN_TIME.id -> two(t.toInt())
            Pids.DISTANCE_WITH_MIL.id -> two(137)
            Pids.FUEL_LEVEL.id -> intArrayOf((model.fuelLevel(t) * 255 / 100).toInt())
            Pids.WARMUPS_SINCE_CLEAR.id -> intArrayOf(14)
            Pids.DISTANCE_SINCE_CLEAR.id -> two(412)
            Pids.BAROMETRIC_PRESSURE.id -> intArrayOf(100)
            Pids.CATALYST_TEMP_B1S1.id -> two(((model.catalystTemp(t) + 40) * 10).toInt())
            Pids.CONTROL_MODULE_VOLTAGE.id -> two((model.voltage(t) * 1000).toInt())
            Pids.ABSOLUTE_LOAD.id -> two((model.load(t) * 255 / 100).toInt())
            Pids.EQUIVALENCE_RATIO.id -> two((1.0 * 32768).toInt())
            Pids.RELATIVE_THROTTLE.id -> intArrayOf((model.throttle(t) * 255 / 100).toInt())
            Pids.AMBIENT_TEMP.id -> intArrayOf((18.0 + 40).toInt())
            Pids.ACCEL_PEDAL_D.id -> intArrayOf((model.throttle(t) * 2.1).toInt().coerceAtMost(255))
            Pids.COMMANDED_THROTTLE.id -> intArrayOf((model.throttle(t) * 255 / 100).toInt())
            Pids.TIME_WITH_MIL.id -> two(96)
            Pids.TIME_SINCE_CLEAR.id -> two(1340)
            Pids.FUEL_TYPE.id -> intArrayOf(0x01) // бензин
            Pids.OIL_TEMP.id -> intArrayOf((model.oilTemp(t) + 40).toInt())
            Pids.FUEL_RATE.id -> two((com.obdscanner.core.obd.FuelEconomy
                .litresPerHourFromMaf(model.maf(t)) * 20).toInt())
            else -> null
        }?.map { it.coerceIn(0, 255) }?.toIntArray()
    }
}
