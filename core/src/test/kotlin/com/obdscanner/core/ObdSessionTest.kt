package com.obdscanner.core

import com.obdscanner.core.dtc.DtcSource
import com.obdscanner.core.elm.ObdProtocol
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.obd.VinDecoder
import com.obdscanner.core.session.ConnectOutcome
import com.obdscanner.core.session.ConnectStage
import com.obdscanner.core.session.ElmAdapter
import com.obdscanner.core.session.ObdChannel
import com.obdscanner.core.session.ObdSession
import com.obdscanner.core.sim.SimulatedEcu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сквозной прогон протокола против симулятора ЭБУ: подключение, опрос
 * параметров, ошибки, VIN, стоп-кадр. Время в тестах виртуальное - сам тест
 * идёт мгновенно, а значения приборов предсказуемы.
 */
class ObdSessionTest {

    /** Виртуальные часы: сколько "прошло" решает тест, а не планировщик ОС. */
    private class FakeClock(var millis: Long = 10_000) {
        fun now(): Long = millis
        fun sleep(ms: Long) { millis += ms }
    }

    private fun session(
        clock: FakeClock = FakeClock(),
        ecu: SimulatedEcu = SimulatedEcu(clock = clock::now)
    ): Pair<ObdSession, SimulatedEcu> {
        val adapter = ElmAdapter(ecu, sleep = clock::sleep, now = clock::now)
        return ObdSession(adapter) to ecu
    }

    @Test
    /** подключение проходит все этапы и находит протокол. */
    fun connectRunsAllStagesAndDetectsProtocol() {
        val (obd, _) = session()
        val stages = ArrayList<ConnectStage>()
        val outcome = obd.connect { stages.add(it) }
        assertTrue(outcome.toString(), outcome is ConnectOutcome.Connected)
        val connected = outcome as ConnectOutcome.Connected
        assertTrue(connected.adapterId.contains("ELM327"))
        assertEquals(ObdProtocol.ISO15765_11_500, connected.protocol)
        assertTrue(ConnectStage.RESET in stages)
        assertTrue(ConnectStage.DONE in stages)
        // Машина сообщила про обороты, скорость и температуру.
        assertTrue(obd.supports(Pids.RPM.id))
        assertTrue(obd.supports(Pids.SPEED.id))
        assertTrue(obd.supports(Pids.COOLANT_TEMP.id))
        // А вот этих PID у неё нет - и приложение не должно их спрашивать.
        assertFalse(obd.supports(0x0A))
        assertFalse(obd.supports(0x61))
    }

    @Test
    /** карты PID спрашиваются только существующие. */
    fun onlyExistingPidRangesAreQueried() {
        val clock = FakeClock()
        val ecu = SimulatedEcu(clock = clock::now)
        val (obd, _) = session(clock, ecu)
        obd.connect()
        // ATZ + 4 настройки + ATAT + ATSP + 0100 + ATDPN + карты 0x20 и 0x40 = 11.
        // Карта 0x60 не запрашивается: в 0x40 её признака нет.
        assertEquals(11, ecu.requestCount)
        assertTrue(0x40 in obd.supportedPids)
        assertFalse(0x60 in obd.supportedPids)
    }

    @Test
    /** значения читаются и связаны между собой. */
    fun liveValuesAreConsistent() {
        val clock = FakeClock()
        val (obd, _) = session(clock, SimulatedEcu(clock = clock::now))
        obd.connect()
        // Сдвигаем виртуальное время в середину разгона.
        clock.millis += 20_000
        val values = obd.readAll(listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP, Pids.THROTTLE))
        val rpm = values[Pids.RPM.id]!!
        val speed = values[Pids.SPEED.id]!!
        assertTrue("обороты $rpm", rpm in 900.0..6500.0)
        assertTrue("скорость $speed", speed > 1.0)
        assertTrue("двигатель прогрет", values[Pids.COOLANT_TEMP.id]!! > 30.0)
        assertTrue("газ нажат", values[Pids.THROTTLE.id]!! > 20.0)
    }

    @Test
    /** на холостом ходу скорость ноль, а обороты есть. */
    fun atIdleSpeedIsZeroButRpmIsNot() {
        val clock = FakeClock()
        val (obd, _) = session(clock, SimulatedEcu(clock = clock::now))
        obd.connect()
        assertEquals(0.0, obd.read(Pids.SPEED)!!, 1e-9)
        assertTrue(obd.read(Pids.RPM)!! in 700.0..900.0)
    }

    @Test
    /** неподдерживаемый PID возвращает null, а не ноль. */
    fun unsupportedPidReturnsNull() {
        val (obd, _) = session()
        obd.connect()
        assertNull(obd.read(Pids.FUEL_PRESSURE))
        assertNull(obd.read(Pids.REFERENCE_TORQUE))
    }

    @Test
    /** ошибки читаются из всех трёх режимов. */
    fun dtcsAreReadFromAllThreeServices() {
        val (obd, _) = session()
        obd.connect()
        assertEquals(
            listOf("P0301", "P0171", "P0420"),
            obd.readDtcs(DtcSource.STORED).map { it.code }
        )
        assertEquals(listOf("P0133"), obd.readDtcs(DtcSource.PENDING).map { it.code })
        assertEquals(listOf("P0420"), obd.readDtcs(DtcSource.PERMANENT).map { it.code })
        assertEquals(DtcSource.PENDING, obd.readDtcs(DtcSource.PENDING).first().source)
    }

    @Test
    /** сброс гасит сохранённые и ожидающие, но не постоянные. */
    fun clearRemovesStoredButNotPermanent() {
        val (obd, _) = session()
        obd.connect()
        assertTrue(obd.clearDtcs())
        assertTrue(obd.readDtcs(DtcSource.STORED).isEmpty())
        assertTrue(obd.readDtcs(DtcSource.PENDING).isEmpty())
        assertEquals(listOf("P0420"), obd.readDtcs(DtcSource.PERMANENT).map { it.code })
    }

    @Test
    /** статус тестов виден и учитывает число ошибок. */
    fun monitorStatusIsReadable() {
        val (obd, _) = session()
        obd.connect()
        val status = obd.readMonitorStatus()!!
        assertTrue(status.milOn)
        assertEquals(3, status.dtcCount)
        assertFalse(status.readyForInspection)
    }

    @Test
    /** VIN собирается из многокадрового ответа и разбирается. */
    fun vinIsAssembledAndDecoded() {
        val (obd, ecu) = session()
        obd.connect()
        val vin = obd.readVin()
        assertEquals(ecu.vin, vin)
        val info = VinDecoder.decode(vin!!)!!
        assertTrue(info.country!!.contains("Росси"))
        assertEquals("LADA (АвтоВАЗ)", info.manufacturer)
    }

    @Test
    /** имя блока и калибровка читаются как текст. */
    fun ecuNameAndCalibrationAreText() {
        val (obd, ecu) = session()
        obd.connect()
        assertEquals(ecu.ecuName, obd.readEcuName())
        assertEquals(ecu.calibrationId, obd.readCalibrationId())
    }

    @Test
    /** стоп-кадр отдаёт значения на момент ошибки. */
    fun freezeFrameReturnsStoredValues() {
        val (obd, _) = session()
        obd.connect()
        val frame = obd.readFreezeFrame(listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP))
        assertEquals(3, frame.size)
        // 42-я секунда цикла - машина едет по трассе.
        assertTrue("скорость ${frame[Pids.SPEED.id]}", frame[Pids.SPEED.id]!! > 80.0)
        assertTrue(frame[Pids.RPM.id]!! > 1500.0)
    }

    @Test
    /** стоп-кадра нет, если ошибки сброшены. */
    fun noFreezeFrameAfterClear() {
        val (obd, _) = session()
        obd.connect()
        obd.clearDtcs()
        assertTrue(obd.readFreezeFrame(listOf(Pids.RPM)).isEmpty())
    }

    @Test
    /** напряжение на разъёме читается через ATRV. */
    fun adapterVoltageIsRead() {
        val (obd, _) = session()
        obd.connect()
        val volts = obd.adapterVoltage()!!
        assertTrue("напряжение $volts", volts in 11.0..15.0)
    }

    @Test
    /** мёртвый адаптер не вешает подключение. */
    fun silentAdapterTimesOutInsteadOfHanging() {
        // Канал, который ничего не отвечает: должна вернуться ошибка таймаута,
        // а не бесконечное ожидание.
        val clock = FakeClock()
        val silent = object : ObdChannel {
            override fun write(bytes: ByteArray) = Unit
            override fun read(dst: ByteArray): Int = 0
            override fun close() = Unit
        }
        val obd = ObdSession(ElmAdapter(silent, sleep = clock::sleep, now = clock::now))
        val outcome = obd.connect()
        assertTrue(outcome is ConnectOutcome.Failed)
        assertEquals(ConnectStage.RESET, (outcome as ConnectOutcome.Failed).stage)
        assertTrue(outcome.message.isNotEmpty())
    }

    @Test
    /** машина без связи с шиной даёт понятную ошибку. */
    fun busFailureGivesReadableError() {
        // Адаптер отвечает на AT-команды, но на запрос данных - UNABLE TO CONNECT
        // (классика: зажигание выключено).
        val clock = FakeClock()
        val noBus = object : ObdChannel {
            private val out = StringBuilder()
            override fun write(bytes: ByteArray) {
                val command = String(bytes).trim().uppercase()
                out.append(if (command.startsWith("AT")) "OK\r\r>" else "UNABLE TO CONNECT\r\r>")
            }
            override fun read(dst: ByteArray): Int {
                if (out.isEmpty()) return 0
                val n = minOf(dst.size, out.length)
                for (i in 0 until n) dst[i] = out[i].code.toByte()
                out.delete(0, n)
                return n
            }
            override fun close() = Unit
        }
        val obd = ObdSession(ElmAdapter(noBus, sleep = clock::sleep, now = clock::now))
        val outcome = obd.connect() as ConnectOutcome.Failed
        assertEquals(ConnectStage.PROTOCOL, outcome.stage)
        assertNotNull(outcome.error)
        assertTrue(outcome.message.contains("зажигание", ignoreCase = true))
    }
}
