package com.obdscanner.core

import com.obdscanner.core.elm.AdapterChip
import com.obdscanner.core.elm.AdapterProbe
import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.obd.InUsePerformance
import com.obdscanner.core.obd.Mode09
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
 * Режим 09 (данные об автомобиле и прошивке) и опознание адаптера.
 */
class Mode09Test {

    private class FakeClock(var millis: Long = 10_000) {
        fun now(): Long = millis
        fun sleep(ms: Long) { millis += ms }
    }

    private fun connected(): ObdSession {
        val clock = FakeClock()
        val ecu = SimulatedEcu(clock = clock::now)
        val session = ObdSession(ElmAdapter(ecu, sleep = clock::sleep, now = clock::now))
        session.connect()
        return session
    }

    @Test
    /** ведущий байт отбрасывается по длине, а не по значению. */
    fun dropsCountByteByLength() {
        // VIN: 17 байт текста плюс счётчик - остаток от 17 равен 1.
        val vin = IntArray(18) { if (it == 0) 0x01 else 'A'.code }
        assertEquals(17, Mode09.dropCountByte(vin, 17).size)
        // Без счётчика ничего не отбрасывается.
        assertEquals(17, Mode09.dropCountByte(IntArray(17) { 'A'.code }, 17).size)
        // Счётчик элементов режима 09 PID 08 равен 20 - по значению его бы
        // приняли за данные, по длине он отбрасывается верно.
        val ipt = IntArray(41) { if (it == 0) 20 else 1 }
        assertEquals(40, Mode09.dropCountByte(ipt, 2).size)
    }

    @Test
    /** несколько идентификаторов калибровки читаются все, а не только первый. */
    fun readsSeveralCalibrationIds() {
        val first = "SIM0001-OBD".padEnd(16, '\u0000')
        val second = "SIM0002-TCM".padEnd(16, '\u0000')
        val payload = intArrayOf(0x02) + (first + second).map { it.code }.toIntArray()
        val ids = Mode09.calibrationIds(payload)
        assertEquals(listOf("SIM0001-OBD", "SIM0002-TCM"), ids)
    }

    @Test
    /** CVN - это байты контрольной суммы, а не текст. */
    fun readsCvnAsHex() {
        val payload = intArrayOf(0x01, 0x5A, 0x3C, 0x11, 0x08)
        assertEquals(listOf("5A3C1108"), Mode09.calibrationVerificationNumbers(payload))
    }

    @Test
    /** счётчики наработки: порядок стандарта и отношение "завершено/условия". */
    fun decodesInUsePerformance() {
        val values = intArrayOf(120, 160, 6, 98, 0, 0, 74, 96)
        val payload = ArrayList<Int>()
        payload.add(values.size)
        values.forEach { payload.add((it shr 8) and 0xFF); payload.add(it and 0xFF) }
        val ipt = InUsePerformance.decode(payload.toIntArray(), compressionIgnition = false)

        assertEquals(120, ipt.obdConditions)
        assertEquals(160, ipt.ignitionCycles)
        assertEquals(6, ipt.value("CATCOMP1"))
        assertEquals(98, ipt.value("CATCOND1"))
        // Отношение - то самое число, которое нормируется законом.
        assertEquals(6.0 / 98.0, ipt.ratio("CATCOMP1", "CATCOND1")!!, 1e-9)
        // Делить на ноль не пробуем: условий не было - значит отношения нет.
        assertNull(ipt.ratio("CATCOMP2", "CATCOND2"))
    }

    @Test
    /** у дизеля набор счётчиков другой, и имена не должны путаться. */
    fun decodesCompressionIgnitionCounters() {
        val values = intArrayOf(10, 20, 30, 40)
        val payload = ArrayList<Int>()
        values.forEach { payload.add(0); payload.add(it) }
        val diesel = InUsePerformance.decode(payload.toIntArray(), compressionIgnition = true)
        assertEquals(30, diesel.value("HCCATCOMP"))
        assertNull(diesel.value("CATCOMP1"))
        val petrol = InUsePerformance.decode(payload.toIntArray(), compressionIgnition = false)
        assertEquals(30, petrol.value("CATCOMP1"))
    }

    @Test
    /** сквозное чтение режима 09 через сессию: CALID, CVN, счётчики. */
    fun sessionReadsVehicleInfo() {
        val session = connected()
        assertEquals(listOf("SIM0001-OBD"), session.readCalibrationIds())
        assertEquals(listOf("5A3C1108"), session.readCalibrationVerificationNumbers())
        val support = session.readVehicleInfoSupport()
        assertTrue(0x02 in support)
        assertTrue(0x08 in support)
        val ipt = session.readInUsePerformance(compressionIgnition = false)
        assertNotNull(ipt)
        assertEquals(120, ipt!!.obdConditions)
        assertEquals(160, ipt.ignitionCycles)
        // Пар "завершено/условия" должно быть столько, сколько прислал блок.
        assertTrue(ipt.pairs.isNotEmpty())
    }

    @Test
    /** код, записавший стоп-кадр: пара нулей - это "кадра нет", а не P0000. */
    fun readsFreezeFrameDtc() {
        val session = connected()
        // Симулятор отдаёт стоп-кадр без PID 02, значит ответа нет.
        assertNull(session.readFreezeFrameDtc())
    }

    @Test
    /** опознание адаптера: версия, которой не выпускалось, выдаёт клон. */
    fun identifiesCloneByVersion() {
        val answers = mapOf(
            "ATI" to "ELM327 v1.5",
            "AT@1" to "?",
            "STDI" to "?",
            "AT@2" to "?"
        )
        val probe = AdapterProbe { command ->
            ElmReply.parse((answers[command] ?: "OK") + "\r\r>", echo = command)
        }
        val info = probe.identify()
        assertEquals("1.5", info.version)
        assertEquals(AdapterChip.ELM327_CLONE, info.chip)
        // "?" - это не ответ, и в поля его записывать нельзя.
        assertEquals("", info.identifier)
    }

    @Test
    /** чип STN узнаётся по ответу на ST-команду, а не по версии ELM. */
    fun identifiesStnChip() {
        val answers = mapOf(
            "ATI" to "ELM327 v1.3a",
            "STDI" to "OBDLink MX 1.3",
            "STSN" to "123456789",
            "AT@1" to "OBDLink"
        )
        val probe = AdapterProbe { command ->
            ElmReply.parse((answers[command] ?: "OK") + "\r\r>", echo = command)
        }
        val info = probe.identify()
        assertEquals(AdapterChip.STN, info.chip)
        assertEquals("123456789", info.serial)
    }

    @Test
    /** проверка команд отделяет поддержанные от непонятых. */
    fun checksCommandsAndReportsMissing() {
        val probe = AdapterProbe { command ->
            // Клон не умеет фильтр приёма и калибровку вольтметра.
            val answer = when {
                command.startsWith("ATCRA") || command.startsWith("ATCV") -> "?"
                command == "ATI" -> "ELM327 v2.1"
                else -> "OK"
            }
            ElmReply.parse("$answer\r\r>", echo = command)
        }
        val info = probe.check()
        assertTrue(info.checks.isNotEmpty())
        assertFalse(info.canScanModules)
        assertFalse(info.supportsVoltageCalibration)
        assertEquals(3, info.missing.size)
        // Свой заголовок запроса такой адаптер всё-таки умеет.
        assertTrue(info.checks.first { it.command.startsWith("ATSH") }.supported)
    }
}
