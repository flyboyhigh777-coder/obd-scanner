package com.obdscanner.core

import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.elm.ObdPayload
import com.obdscanner.core.modules.ModuleProtocol
import com.obdscanner.core.modules.ModuleReply
import com.obdscanner.core.modules.ModuleScan
import com.obdscanner.core.modules.UdsStatus
import com.obdscanner.core.obd.Pids
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
 * Блоки вне OBD-II: ABS, подушки, АКПП.
 *
 * Лампа ABS горит на панели, а приложение показывает "ошибок нет" - и оно не
 * врёт: режим 03 спрашивает только двигатель. Остальные блоки надо звать по
 * их адресам и на их языке.
 */
class ModuleScanTest {

    private class FakeClock(var millis: Long = 10_000) {
        fun now(): Long = millis
        fun sleep(ms: Long) { millis += ms }
    }

    private fun frames(vararg lines: String) = ObdPayload.frames(ElmReply(lines.toList()))

    /** UDS: три байта кода плюс статус. Третий байт - уточнение отказа. */
    @Test
    fun udsAnswerIsParsed() {
        val reply = ModuleScan.parse(frames("5902FF40311109"))
        assertTrue(reply.toString(), reply is ModuleReply.Codes)
        val codes = (reply as ModuleReply.Codes)
        assertEquals(ModuleProtocol.UDS, codes.protocol)
        assertEquals(1, codes.dtcs.size)
        val dtc = codes.dtcs.first()
        assertEquals("C0031", dtc.code)
        assertEquals("C0031-11", dtc.full)
        assertEquals(true, dtc.active)
        assertEquals(true, dtc.confirmed)
        assertEquals(false, dtc.lampRequested)
    }

    /** Несколько кодов подряд в одном ответе. */
    @Test
    fun severalUdsCodes() {
        val reply = ModuleScan.parse(frames("5902FF403111094040080A"))
        val codes = (reply as ModuleReply.Codes).dtcs
        assertEquals(listOf("C0031-11", "C0040-08"), codes.map { it.full })
    }

    /** KWP2000: код из двух байт и статус, без уточнения отказа. */
    @Test
    fun kwpAnswerIsParsed() {
        val reply = ModuleScan.parse(frames("5801901209"))
        assertTrue(reply.toString(), reply is ModuleReply.Codes)
        val codes = reply as ModuleReply.Codes
        assertEquals(ModuleProtocol.KWP, codes.protocol)
        assertEquals("B1012", codes.dtcs.single().code)
        assertNull("у KWP третьего байта нет", codes.dtcs.single().failureType)
    }

    /** Блок ответил отказом - это тоже находка: по адресу кто-то есть. */
    @Test
    fun refusalStillMeansTheModuleIsThere() {
        val reply = ModuleScan.parse(frames("7F1933"))
        assertTrue(reply.toString(), reply is ModuleReply.Refused)
        assertEquals(0x33, (reply as ModuleReply.Refused).nrc)
        assertTrue("причина объяснена: ${reply.reason}", reply.reason.isNotEmpty())
    }

    /** Молчание - это молчание, а не пустой список кодов. */
    @Test
    fun silenceIsNotAnAnswer() {
        assertNull(ModuleScan.parse(ObdPayload.frames(ElmReply(emptyList()))))
    }

    /** Блок ответил, но кодов у него нет - это уже ответ. */
    @Test
    fun emptyCodeListIsAnAnswer() {
        val reply = ModuleScan.parse(frames("5902FF"))
        assertTrue(reply.toString(), reply is ModuleReply.Codes)
        assertTrue((reply as ModuleReply.Codes).dtcs.isEmpty())
    }

    /** Адрес ответа - на восемь больше адреса запроса. */
    @Test
    fun responseAddressFollowsTheRequest() {
        assertEquals(0x768, ModuleScan.responseFor(0x760))
        assertEquals(0x7E8, ModuleScan.responseFor(0x7E0))
        assertTrue(ModuleScan.isStandardObd(0x7E0))
        assertFalse(ModuleScan.isStandardObd(0x760))
    }

    /**
     * Сквозь всю сессию: перебор адресов находит выдуманные блоки симулятора -
     * один с кодами по UDS, один отказавший, один на старом KWP2000.
     */
    @Test
    fun scanFindsModulesOnTheBus() {
        val clock = FakeClock()
        val obd = ObdSession(ElmAdapter(SimulatedEcu(clock = clock::now), sleep = clock::sleep, now = clock::now))
        obd.connect()
        assertTrue("на CAN перебор возможен", obd.canScanModules)

        val found = obd.scanModules()
        val byAddress = found.associateBy { it.address }

        val abs = byAddress[0x760]
        assertNotNull("блок с кодами найден: ${found.map { it.hex }}", abs)
        assertEquals(listOf("C0031-11", "C0040-08"), abs!!.dtcs.map { it.full })
        assertTrue("буква кода говорит, что это шасси", abs.systems.first().contains("ABS"))

        val refused = byAddress[0x7A0]
        assertNotNull("отказавший блок - тоже находка", refused)
        assertTrue(refused!!.reply is ModuleReply.Refused)

        val kwp = byAddress[0x730]
        assertNotNull("блок на KWP2000 найден", kwp)
        assertEquals(listOf("B1234"), kwp!!.dtcs.map { it.full })
    }

    /**
     * После перебора приборы обязаны читаться по-прежнему.
     *
     * Перебор сдвигает адрес запроса и фильтр приёма у адаптера. Если их не
     * вернуть, приложение после поиска блоков показывает замершие стрелки, и
     * выглядит это как отвалившийся адаптер.
     */
    @Test
    fun pollingStillWorksAfterTheScan() {
        val clock = FakeClock()
        val obd = ObdSession(ElmAdapter(SimulatedEcu(clock = clock::now), sleep = clock::sleep, now = clock::now))
        obd.connect()

        obd.scanModules(from = 0x700, to = 0x770)

        val rpm = obd.read(Pids.RPM)
        assertNotNull("обороты читаются после перебора", rpm)
        assertTrue("и значение осмысленное: $rpm", rpm!! in 300.0..7000.0)
    }

    /**
     * Блок двигателя по UDS: коды со статусом читаются до сброса, а после
     * режима 04 их там больше нет. Именно поэтому их перечитывают и кладут в
     * архив перед сбросом - иначе P0500 пропадает бесследно.
     */
    @Test
    fun engineUdsCodesAreGoneAfterClear() {
        val clock = FakeClock()
        val obd = ObdSession(ElmAdapter(SimulatedEcu(clock = clock::now), sleep = clock::sleep, now = clock::now))
        obd.connect()

        val before = obd.readModule(ModuleScan.ENGINE_ADDRESS)
        assertNotNull("блок двигателя ответил по UDS", before)
        val codes = before!!.dtcs
        assertTrue("коды режима 03 видны и по UDS: ${codes.map { it.full }}", codes.any { it.code == "P0301" })
        assertEquals("сохранённый код - есть сейчас", true, codes.first { it.code == "P0301" }.active)
        assertTrue(codes.first { it.code == "P0301" }.statusText!!.contains("есть сейчас"))

        assertTrue(obd.clearDtcs())
        val after = obd.readModule(ModuleScan.ENGINE_ADDRESS)
        assertNotNull(after)
        assertTrue("после сброса память пуста: ${after!!.dtcs.map { it.full }}", after.dtcs.isEmpty())
        // И обычный опрос после смены адреса работает.
        assertNotNull(obd.read(Pids.RPM))
    }

    /** Статус UDS одной фразой - главное, что отличает неисправность от записи. */
    @Test
    fun udsStatusInWords() {
        assertTrue(UdsStatus.describe(0x09)!!.startsWith("ошибка есть сейчас"))
        assertTrue(UdsStatus.describe(0x88)!!.contains("подтверждена"))
        assertTrue(UdsStatus.describe(0x88)!!.contains("лампу"))
        assertTrue(UdsStatus.describe(0x50)!!.startsWith("тест не проходил после сброса"))
        assertTrue(UdsStatus.describe(0x24)!!.startsWith("ожидает подтверждения"))
        assertTrue(UdsStatus.describe(0x50)!!.endsWith("(0x50)"))
        assertNull(UdsStatus.describe(null))
        assertEquals(true, UdsStatus.isReal(0x08))
        assertEquals(false, UdsStatus.isReal(0x50))
    }

    /** Перебор можно остановить - двести адресов это полминуты ожидания. */
    @Test
    fun scanCanBeStopped() {
        val clock = FakeClock()
        val obd = ObdSession(ElmAdapter(SimulatedEcu(clock = clock::now), sleep = clock::sleep, now = clock::now))
        obd.connect()

        var seen = 0
        obd.scanModules(shouldStop = { seen >= 10 }, onProgress = { done, _, _ -> seen = done })
        assertTrue("остановились почти сразу: $seen", seen in 1..12)
    }
}
