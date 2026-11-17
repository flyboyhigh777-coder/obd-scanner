package com.obdscanner.core

import com.obdscanner.core.diag.QuickCheck
import com.obdscanner.core.monitor.MonitorIds
import com.obdscanner.core.session.ElmAdapter
import com.obdscanner.core.session.ObdSession
import com.obdscanner.core.sim.SimulatedEcu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пропуски воспламенения по цилиндрам (режим 06, мониторы 0xA0..0xAC).
 *
 * Это единственное место во всём OBD-II, где видно, КАКОЙ цилиндр пропускает.
 * Код P030x появляется только когда доля пропусков перевалит порог, а мотор
 * начинает троить гораздо раньше - и всё это время ответ лежит здесь.
 */
class MisfireMonitorTest {

    private class FakeClock(var millis: Long = 10_000) {
        fun now(): Long = millis
        fun sleep(ms: Long) { millis += ms }
    }

    private fun session(clock: FakeClock): ObdSession {
        val ecu = SimulatedEcu(clock = clock::now)
        val obd = ObdSession(ElmAdapter(ecu, sleep = clock::sleep, now = clock::now))
        obd.connect()
        return obd
    }

    /**
     * Нумерация по стандарту: 0xA1 - общие данные, 0xA2 - ПЕРВЫЙ цилиндр.
     *
     * Круглый 0xA0 занят картой поддерживаемых мониторов, отсюда и смещение.
     * Проверяется оно не для красоты: сдвиг на единицу отправит человека
     * менять свечу не в том цилиндре.
     */
    @Test
    fun cylinderNumbersFollowTheStandard() {
        assertNull("0xA0 - карта поддержки, а не цилиндр", MonitorIds.misfireCylinder(0xA0))
        assertNull("0xA1 - общие данные, а не цилиндр", MonitorIds.misfireCylinder(0xA1))
        assertEquals(1, MonitorIds.misfireCylinder(0xA2))
        assertEquals(2, MonitorIds.misfireCylinder(0xA3))
        assertEquals(4, MonitorIds.misfireCylinder(0xA5))
        assertEquals(12, MonitorIds.misfireCylinder(0xAD))
        assertNull("за двенадцатым цилиндром мониторов пропусков нет", MonitorIds.misfireCylinder(0xAE))

        assertTrue(MonitorIds.isMisfire(0xA1))
        assertTrue(MonitorIds.isMisfire(0xA4))
        assertFalse("карта поддержки - не пропуски", MonitorIds.isMisfire(0xA0))
        assertFalse("лямбда-зонд - не пропуски", MonitorIds.isMisfire(0x01))

        // Проверка на сдвиг: у монитора первого цилиндра в названии должна
        // стоять единица и не должно стоять двойки.
        val first = MonitorIds.name(0xA2)
        assertTrue(first, first.contains("1"))
        assertFalse(first, first.contains("2"))
        val fourth = MonitorIds.name(0xA5)
        assertTrue(fourth, fourth.contains("4"))
        assertFalse(fourth, fourth.contains("5"))
    }

    /** У четырёхцилиндрового спрашиваем общие данные и четыре цилиндра, не больше. */
    @Test
    fun probeCoversEveryCylinderAndNothingMore() {
        assertEquals(listOf(0xA1, 0xA2, 0xA3, 0xA4, 0xA5), MonitorIds.misfireProbe(4))
        assertEquals(listOf(0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7), MonitorIds.misfireProbe(6))
        // Восьмицилиндровый - восемь, а не шестнадцать: лишние номера это
        // лишние секунды ожидания на каждом.
        assertEquals(9, MonitorIds.misfireProbe(8).size)
        assertEquals(0xAD, MonitorIds.misfireProbe(12).last())
    }

    /**
     * Блок не объявил мониторы пропусков - и всё равно отвечает на них.
     *
     * Так ведут себя настоящие ЭБУ, и однажды мы на этом уже обожглись с
     * расходомером: верить карте поддержки нельзя, надо спрашивать.
     */
    @Test
    fun misfireMonitorsAreAskedEvenWhenNotDeclared() {
        val clock = FakeClock()
        val obd = session(clock)

        val declared = obd.readSupportedMonitorIds()
        assertFalse("цилиндры в карте поддержки не объявлены", declared.contains(0xA2))

        val tests = obd.readAllMonitorTests(cylinders = 4)
        val byCylinder = tests.mapNotNull { test ->
            MonitorIds.misfireCylinder(test.mid)?.let { it to test }
        }.toMap()

        assertEquals("все четыре цилиндра прочитаны: ${tests.map { it.mid }}", 4, byCylinder.size)
    }

    /**
     * Главное: по числам видно, какой цилиндр троит.
     */
    @Test
    fun theMisfiringCylinderStandsOut() {
        val clock = FakeClock()
        val obd = session(clock)

        val current = obd.readAllMonitorTests(cylinders = 4)
            .filter { it.tid == 0x0C }
            .mapNotNull { test -> MonitorIds.misfireCylinder(test.mid)?.let { it to test.rawValue } }
            .toMap()

        assertEquals(4, current.size)
        val worst = current.maxByOrNull { it.value }!!
        assertEquals("троит третий цилиндр: $current", 3, worst.key)
        assertTrue("и отрыв от остальных очевидный", worst.value > 50)
        assertTrue("остальные почти чистые", current.filterKeys { it != 3 }.values.all { it < 10 })
    }

    /** В отчёте пропуски идут отдельным разделом, а не теряются среди зондов. */
    @Test
    fun reportHasItsOwnMisfireSection() {
        val clock = FakeClock()
        val report = QuickCheck(session(clock), cylinders = 4, now = clock::now).run()

        assertTrue("мониторы пропусков попали в отчёт", report.misfireTests.isNotEmpty())
        val text = report.blocks().joinToString("\n") { it.toString() }
        assertTrue("есть раздел про цилиндры", text.contains("Пропуски воспламенения по цилиндрам"))
        assertTrue("и назван виновный цилиндр", text.contains("Цилиндр 3"))
    }

    /**
     * Мотор, у которого пропусков нет, тоже должен быть виден: иначе не с чем
     * сравнивать, когда они появятся.
     */
    @Test
    fun healthyCylindersAreShownToo() {
        val clock = FakeClock()
        val report = QuickCheck(session(clock), cylinders = 4, now = clock::now).run()

        val cylinders = report.misfireTests.mapNotNull { MonitorIds.misfireCylinder(it.mid) }.distinct()
        assertEquals("показаны все четыре, а не только больной", listOf(1, 2, 3, 4), cylinders.sorted())
        assertNotNull("общие данные тоже есть", report.misfireTests.firstOrNull { it.mid == 0xA1 })
    }
}
