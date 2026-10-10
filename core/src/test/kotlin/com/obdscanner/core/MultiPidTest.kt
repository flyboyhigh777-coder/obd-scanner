package com.obdscanner.core

import com.obdscanner.core.elm.Elm
import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.elm.ObdPayload
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.session.ElmAdapter
import com.obdscanner.core.session.ObdChannel
import com.obdscanner.core.session.ObdSession
import com.obdscanner.core.sim.SimulatedEcu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пакетный запрос - единственное, что реально ускоряет опрос: шесть
 * параметров одной командой вместо шести команд, то есть один круг через
 * адаптер и шину вместо шести.
 */
class MultiPidTest {

    private class FakeClock(var millis: Long = 0) {
        fun sleep(ms: Long) { millis += ms }
        fun now(): Long = millis
    }

    private fun session(channel: ObdChannel, clock: FakeClock): ObdSession {
        val obd = ObdSession(ElmAdapter(channel, sleep = clock::sleep, now = clock::now))
        obd.connect()
        return obd
    }

    @Test
    fun requestPacksSeveralPids() {
        assertEquals("010C0D05", Elm.request(0x01, listOf(0x0C, 0x0D, 0x05)))
    }

    /** Больше шести ELM327 не принимает - лишние отбрасываем, а не ломаемся. */
    @Test
    fun requestIsCappedAtSix() {
        val many = listOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11)
        val text = Elm.request(0x01, many)
        assertEquals("0104050B0C0D0F", text)
    }

    /** Ответ режется по длине каждого параметра: без неё границы не найти. */
    @Test
    fun responseIsSplitByPidLength() {
        val reply = ElmReply(listOf("41 0C 1A F8 0D 00 05 5A"))
        val data = ObdPayload.multi(reply, 0x01, listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP))
        assertEquals(3, data.size)
        assertArrayEquals(intArrayOf(0x1A, 0xF8), data[Pids.RPM.id]!!)
        assertArrayEquals(intArrayOf(0x00), data[Pids.SPEED.id]!!)
        assertArrayEquals(intArrayOf(0x5A), data[Pids.COOLANT_TEMP.id]!!)
    }

    /** Блок может прислать не всё - это не ошибка, просто карта меньше. */
    @Test
    fun partialAnswerIsNotAFailure() {
        val reply = ElmReply(listOf("41 0C 1A F8"))
        val data = ObdPayload.multi(reply, 0x01, listOf(Pids.RPM, Pids.SPEED))
        assertEquals(1, data.size)
        assertTrue(Pids.RPM.id in data)
        assertFalse(Pids.SPEED.id in data)
    }

    /**
     * Обрезанный ответ не должен превращаться в значение из мусора: если на
     * параметр не хватило байт, его просто нет.
     */
    @Test
    fun truncatedTailIsDropped() {
        val reply = ElmReply(listOf("41 0C 1A"))
        val data = ObdPayload.multi(reply, 0x01, listOf(Pids.RPM))
        assertTrue(data.isEmpty())
    }

    /** Отказ блока - пустая карта, а не выдуманные нули. */
    @Test
    fun negativeAnswerGivesNothing() {
        val reply = ElmReply(listOf("7F 01 12"))
        assertTrue(ObdPayload.multi(reply, 0x01, listOf(Pids.RPM)).isEmpty())
    }

    /** Сквозь всю сессию: виртуальный ЭБУ отвечает пакетом, значения разбираются. */
    @Test
    fun sessionReadsSeveralPidsInOneRequest() {
        val clock = FakeClock()
        val ecu = SimulatedEcu(clock = clock::now)
        val obd = session(ecu, clock)

        val values = obd.readAll(listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP, Pids.THROTTLE))
        assertTrue(obd.multiPidSupported)
        assertEquals(4, values.size)
        assertTrue("обороты: ${values[Pids.RPM.id]}", values[Pids.RPM.id]!! in 700.0..900.0)
    }

    /**
     * Пакетный запрос экономит обмены - ради этого всё и делалось. Считаем их
     * по числу команд, дошедших до адаптера.
     */
    @Test
    fun batchingCutsTheNumberOfRoundTrips() {
        val clock = FakeClock()
        val pids = listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP, Pids.THROTTLE)

        val counting = CountingEcu(SimulatedEcu(clock = clock::now))
        val obd = session(counting, clock)
        counting.commands = 0
        obd.readAll(pids)
        val batched = counting.commands

        assertEquals("весь набор уходит одной командой", 1, batched)
    }

    /** Считает команды, реально ушедшие в адаптер. */
    private class CountingEcu(private val inner: ObdChannel) : ObdChannel {
        var commands = 0
        override fun write(bytes: ByteArray) {
            commands++
            inner.write(bytes)
        }
        override fun read(dst: ByteArray): Int = inner.read(dst)
        override fun close() = inner.close()
    }

    private fun assertArrayEquals(expected: IntArray, actual: IntArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
