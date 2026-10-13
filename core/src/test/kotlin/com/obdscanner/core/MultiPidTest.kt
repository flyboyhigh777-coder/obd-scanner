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

    /**
     * Один неудачный цикл - ещё не приговор. Блок может промолчать, пока
     * просыпается или пока крутится стартер; раньше этого хватало, чтобы
     * выключить пакетный режим до конца поездки.
     */
    @Test
    fun oneBadCycleDoesNotKillBatching() {
        val clock = FakeClock()
        val flaky = FlakyBatch(SimulatedEcu(clock = clock::now))
        val obd = session(flaky, clock)
        val pids = listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP, Pids.THROTTLE)

        flaky.failBatches = 1
        val duringFailure = obd.readAll(pids)
        assertEquals("параметры всё равно прочитаны - по одному", 4, duringFailure.size)
        assertTrue("пакетный режим остаётся включённым", obd.multiPidSupported)

        val afterFailure = obd.readAll(pids)
        assertEquals(4, afterFailure.size)
        assertTrue(obd.multiPidSupported)
    }

    /** А вот несколько отказов подряд - это уже "не умеет". */
    @Test
    fun repeatedRefusalTurnsBatchingOff() {
        val clock = FakeClock()
        val flaky = FlakyBatch(SimulatedEcu(clock = clock::now))
        val obd = session(flaky, clock)
        val pids = listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP, Pids.THROTTLE)

        flaky.failBatches = 50
        repeat(5) { obd.readAll(pids) }
        assertFalse("пакет не поддержан - больше не пробуем каждый цикл", obd.multiPidSupported)
        assertEquals("но приборы читаются по одному", 4, obd.readAll(pids).size)
    }

    /**
     * Молчит вся шина - виноват не пакетный запрос. Выключать его за обрыв
     * связи значит приехать домой на поштучном опросе без всякой причины.
     */
    @Test
    fun silentBusDoesNotBlameBatching() {
        val clock = FakeClock()
        val flaky = FlakyBatch(SimulatedEcu(clock = clock::now))
        val obd = session(flaky, clock)
        val pids = listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP, Pids.THROTTLE)

        flaky.silent = true
        repeat(5) { assertTrue("данных нет совсем", obd.readAll(pids).isEmpty()) }
        assertTrue("пакетный режим не при чём", obd.multiPidSupported)

        flaky.silent = false
        assertEquals("связь вернулась - и пакет работает", 4, obd.readAll(pids).size)
        assertTrue(obd.multiPidSupported)
    }

    /** Отказ не навсегда: время от времени пакет пробуется заново. */
    @Test
    fun batchingComesBackOnItsOwn() {
        val clock = FakeClock()
        val flaky = FlakyBatch(SimulatedEcu(clock = clock::now))
        val obd = session(flaky, clock)
        val pids = listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP, Pids.THROTTLE)

        flaky.failBatches = 50
        repeat(5) { obd.readAll(pids) }
        assertFalse(obd.multiPidSupported)

        // Блок пришёл в себя, но приложение об этом ещё не знает.
        flaky.failBatches = 0
        var cycles = 0
        while (!obd.multiPidSupported && cycles < 1000) {
            obd.readAll(pids)
            cycles++
        }
        assertTrue("пакет вернулся сам, через $cycles циклов", obd.multiPidSupported)
        assertTrue("но не мгновенно - пробовать каждый цикл дорого: $cycles", cycles > 10)
        assertEquals("и снова одной командой", 4, obd.readAll(pids).size)
    }

    /**
     * Параметр, которого в машине нет, перестаёт отнимать время.
     *
     * Это не теория: человек включает в приборы "показать все", половина
     * номеров машиной не поддерживается, и каждый из них добавляет по обмену
     * в каждый цикл - ровно та скорость, ради которой всё и делалось.
     */
    @Test
    fun pidsTheCarIgnoresAreDropped() {
        val clock = FakeClock()
        val counting = CountingEcu(SimulatedEcu(clock = clock::now))
        val obd = session(counting, clock)
        // 0x0A (давление топлива) виртуальная машина не поддерживает, 0x0C - да.
        val pids = listOf(Pids.RPM, Pids.FUEL_PRESSURE)

        repeat(5) { obd.readAll(pids) }
        assertTrue("неотвечающий номер отмечен", Pids.FUEL_PRESSURE.id in obd.unansweredPids)
        assertFalse("а работающий - нет", Pids.RPM.id in obd.unansweredPids)

        counting.commands = 0
        val values = obd.readAll(pids)
        assertEquals("обороты по-прежнему читаются", 1, values.size)
        assertTrue("и это именно обороты", Pids.RPM.id in values)
        assertEquals("один обмен вместо двух", 1, counting.commands)
    }

    /** Обрыв связи - не повод списывать параметры: молчат ведь все сразу. */
    @Test
    fun silenceOnTheWholeBusDropsNothing() {
        val clock = FakeClock()
        val flaky = FlakyBatch(SimulatedEcu(clock = clock::now))
        val obd = session(flaky, clock)
        val pids = listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP)

        flaky.silent = true
        repeat(6) { obd.readAll(pids) }
        assertTrue("никого не списали: ${obd.unansweredPids}", obd.unansweredPids.isEmpty())

        flaky.silent = false
        assertEquals("связь вернулась - читается всё", 3, obd.readAll(pids).size)
    }

    /**
     * Канал, который может не отвечать на пакетные запросы (команда длиннее
     * одного номера) или молчать вообще - как молчит шина при обрыве.
     */
    private class FlakyBatch(private val inner: ObdChannel) : ObdChannel {
        /** Сколько ближайших пакетных запросов оставить без ответа. */
        var failBatches = 0

        /** Молчание на всё подряд - потеря связи с машиной. */
        var silent = false

        private var canned: ByteArray? = null
        private var at = 0

        override fun write(bytes: ByteArray) {
            val command = String(bytes).trim()
            val batch = command.length > 4 && command.startsWith("01")
            if (silent || (batch && failBatches > 0)) {
                if (batch && failBatches > 0) failBatches--
                canned = "NO DATA\r\r>".toByteArray()
                at = 0
                return
            }
            canned = null
            inner.write(bytes)
        }

        override fun read(dst: ByteArray): Int {
            val answer = canned ?: return inner.read(dst)
            if (at >= answer.size) return 0
            val n = minOf(dst.size, answer.size - at)
            System.arraycopy(answer, at, dst, 0, n)
            at += n
            return n
        }

        override fun close() = inner.close()
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
