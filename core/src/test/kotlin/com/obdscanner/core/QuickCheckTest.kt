package com.obdscanner.core

import com.obdscanner.core.diag.QuickCheck
import com.obdscanner.core.diag.QuickCheckStep
import com.obdscanner.core.session.ElmAdapter
import com.obdscanner.core.session.ObdSession
import com.obdscanner.core.sim.SimulatedEcu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Полная проверка одним нажатием: прогоняется против симулятора целиком, со
 * всеми режимами, и проверяется не только что данные собрались, но и что
 * отчёт читается человеком.
 */
class QuickCheckTest {

    private class FakeClock(var millis: Long = 10_000) {
        fun now(): Long = millis
        fun sleep(ms: Long) { millis += ms }
    }

    private fun session(ecu: SimulatedEcu, clock: FakeClock): ObdSession {
        val session = ObdSession(ElmAdapter(ecu, sleep = clock::sleep, now = clock::now))
        session.connect()
        return session
    }

    @Test
    /** проверка собирает всё: машину, ошибки, готовность, тесты, счётчики. */
    fun collectsEverything() {
        val clock = FakeClock()
        val ecu = SimulatedEcu(clock = clock::now)
        val steps = ArrayList<QuickCheckStep>()
        val report = QuickCheck(session(ecu, clock), now = clock::now).run(onStep = { steps.add(it) })

        assertTrue(QuickCheckStep.ADAPTER in steps)
        assertTrue(QuickCheckStep.DONE in steps)

        assertEquals(ecu.vin, report.vin)
        assertEquals("ECM-EngineControl", report.ecuName)
        assertEquals(listOf("SIM0001-OBD"), report.calibrationIds)
        assertEquals(listOf("5A3C1108"), report.calibrationVerificationNumbers)
        assertTrue(report.supportedPidCount > 20)

        // Ошибки по всем трём режимам.
        assertEquals(4, report.stored.size)
        assertEquals(1, report.pending.size)
        assertEquals(1, report.permanent.size)
        assertEquals(6, report.dtcTotal)

        // Лампа горит, и вывод говорит об этом первым делом.
        assertTrue(report.monitorStatus!!.milOn)
        assertTrue(report.verdict.contains("Check Engine"))

        // Режим 06 нашёл провалившийся тест катализатора - тот самый P0420.
        assertEquals(1, report.failedTests.size)
        assertEquals(0x21, report.failedTests[0].mid)

        // Счётчики наработки прочитаны, стоп-кадр снят, параметры сняты.
        assertNotNull(report.performance)
        assertTrue(report.freezeFrame.isNotEmpty())
        assertTrue(report.live.isNotEmpty())
    }

    @Test
    /** отчёт текстом содержит разделы, коды и провалившийся тест. */
    fun reportTextIsReadable() {
        val clock = FakeClock()
        val ecu = SimulatedEcu(clock = clock::now)
        val text = QuickCheck(session(ecu, clock), now = clock::now).run().toText()

        assertTrue(text.contains("ОТЧЁТ ДИАГНОСТИКИ OBD-II"))
        assertTrue(text.contains(ecu.vin))
        assertTrue(text.contains("P0420"))
        // Расшифровка кода, а не только номер.
        assertTrue(text.contains("катализатор", ignoreCase = true))
        // Провалившийся тест помечен словом, а не цветом - отчёт текстовый.
        assertTrue(text.contains("ВНЕ ПРЕДЕЛОВ"))
        // Готовность тестов: симулятор отдаёт незавершённый тест катализатора.
        assertTrue(text.contains("НЕ завершён"))
        assertTrue(text.contains("CVN 1: 5A3C1108"))
    }

    @Test
    /** прерывание на середине возвращает то, что успели прочитать. */
    fun stopsEarlyAndKeepsWhatWasRead() {
        val clock = FakeClock()
        val ecu = SimulatedEcu(clock = clock::now)
        var steps = 0
        val report = QuickCheck(session(ecu, clock), now = clock::now).run(
            onStep = { steps++ },
            shouldStop = { steps >= 2 }
        )
        // Остановились после данных автомобиля - значит VIN есть, а тестов нет.
        assertNotNull(report.vin)
        assertTrue(report.monitorTests.isEmpty())
        assertTrue(report.live.isEmpty())
    }

    @Test
    /** без режима 06 проверка короче, и это видно по числу запросов. */
    fun monitorTestsCanBeSkipped() {
        val clock = FakeClock()
        val ecu = SimulatedEcu(clock = clock::now)
        val session = session(ecu, clock)
        val before = ecu.requestCount
        val report = QuickCheck(session, withMonitorTests = false, now = clock::now).run()
        val withoutTests = ecu.requestCount - before

        val clock2 = FakeClock()
        val ecu2 = SimulatedEcu(clock = clock2::now)
        val session2 = session(ecu2, clock2)
        val before2 = ecu2.requestCount
        QuickCheck(session2, withMonitorTests = true, now = clock2::now).run()
        val withTests = ecu2.requestCount - before2

        assertTrue(report.monitorTests.isEmpty())
        assertTrue("без режима 06 запросов должно быть меньше", withoutTests < withTests)
        // И отчёт при этом остаётся осмысленным.
        assertFalse(report.verdict.isEmpty())
    }
}
