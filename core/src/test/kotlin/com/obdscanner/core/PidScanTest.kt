package com.obdscanner.core

import com.obdscanner.core.obd.PidScan
import com.obdscanner.core.obd.ScanHit
import com.obdscanner.core.session.ElmAdapter
import com.obdscanner.core.session.ObdSession
import com.obdscanner.core.sim.SimulatedEcu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PidScanTest {

    private class FakeClock(var millis: Long = 0) {
        fun sleep(ms: Long) { millis += ms }
        fun now(): Long = millis
    }

    private fun session(): Pair<ObdSession, FakeClock> {
        val clock = FakeClock()
        val ecu = SimulatedEcu(clock = clock::now)
        val adapter = ElmAdapter(ecu, sleep = clock::sleep, now = clock::now)
        val obd = ObdSession(adapter)
        obd.connect()
        return obd to clock
    }

    /** Перебор находит параметры, которые блок действительно отдаёт. */
    @Test
    fun scanFindsExtendedParameters() {
        val (obd, _) = session()
        val hits = obd.scanService22(0xF400, 0xF40F)
        val found = hits.map { it.pid }
        assertTrue("должен найтись F405: $found", 0xF405 in found)
        assertTrue("должен найтись F40C: $found", 0xF40C in found)
    }

    /** Несуществующие номера в находки не попадают. */
    @Test
    fun scanDoesNotInventParameters() {
        val (obd, _) = session()
        val hits = obd.scanService22(0xF420, 0xF42F)
        assertTrue("пусто: ${hits.map { it.label }}", hits.isEmpty())
    }

    /** Перебор можно остановить - иначе на большом диапазоне его не прервать. */
    @Test
    fun scanStopsOnRequest() {
        val (obd, _) = session()
        var seen = 0
        val hits = obd.scanService22(
            0xF400, 0xF4FF,
            shouldStop = { seen >= 5 },
            onProgress = { done, _, _ -> seen = done }
        )
        assertTrue("перебор прерван: $seen", seen <= 6)
        assertTrue(hits.size < 10)
    }

    /** Прогресс считается от размера диапазона, а не от числа находок. */
    @Test
    fun progressReportsRangeSize() {
        val (obd, _) = session()
        var total = 0
        obd.scanService22(0x1100, 0x1105, onProgress = { _, t, _ -> total = t })
        assertEquals(6, total)
    }

    /** Диапазон ограничивается: перебрать все 65 536 номеров за раз нельзя. */
    @Test
    fun rangeIsClamped() {
        val range = PidScan.clampRange(0x0000, 0xFFFF)
        assertEquals(PidScan.MAX_SPAN, range.size)
        val reversed = PidScan.clampRange(0x0200, 0x0100)
        assertEquals(1, reversed.size)
    }

    /** Сравнение снимков показывает только то, что реально изменилось. */
    @Test
    fun compareShowsOnlyChanged() {
        val before = listOf(
            ScanHit(0x1101, listOf(0x00, 0x10)),
            ScanHit(0x1148, listOf(0x20)),
        )
        val after = listOf(
            ScanHit(0x1101, listOf(0x00, 0x40)),
            ScanHit(0x1148, listOf(0x20)),
        )
        val changes = PidScan.compare(before, after)
        assertEquals(1, changes.size)
        assertEquals(0x1101, changes.first().pid)
        assertEquals(0x30L, changes.first().delta)
    }

    /**
     * Параметр, пропавший во втором снимке, - это почти наверняка потерянный
     * ответ, а не находка: показывать его как изменение значит путать.
     */
    @Test
    fun disappearedParameterIsNotAChange() {
        val before = listOf(ScanHit(0x1101, listOf(0x10)), ScanHit(0x1148, listOf(0x20)))
        val after = listOf(ScanHit(0x1101, listOf(0x10)))
        assertTrue(PidScan.compare(before, after).isEmpty())
    }

    /** Найденный параметр сразу готов к добавлению: номер в виде запроса. */
    @Test
    fun hitFormatsItsRequest() {
        val hit = ScanHit(0x1148, listOf(0x01, 0xF4))
        assertEquals("221148", hit.request)
        assertEquals("1148", hit.label)
        assertEquals("01 F4", hit.hex)
        assertEquals(500L, hit.value)
    }

    /** Повторное чтение известных номеров - для второго снимка. */
    @Test
    fun rereadsKnownParameters() {
        val (obd, _) = session()
        val hits = obd.readService22(listOf(0xF405, 0xF40C, 0xF420))
        assertEquals(2, hits.size)
        assertFalse(hits.any { it.pid == 0xF420 })
    }
}
