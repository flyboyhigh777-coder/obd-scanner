package com.obdscanner.core

import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.elm.ObdPayload
import com.obdscanner.core.elm.ObdText
import com.obdscanner.core.modules.ModuleProtocol
import com.obdscanner.core.modules.ModuleReply
import com.obdscanner.core.modules.ModuleScan
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

    /**
     * Заполнитель из хвоста кадра не становится кодом.
     *
     * Живая машина, адрес 7E0: пятнадцать байт ответа, а адаптер напечатал
     * двадцать - последний кадр CAN всегда длиной восемь байт, и хвост добит
     * байтами AA. Без обрезки по длине из них получался код B2AAA-AA,
     * которого в машине нет, и человек шёл его искать.
     */
    @Test
    fun paddingAtTheEndIsNotACode() {
        val reply = ModuleScan.parse(
            frames(
                "00F",
                "0: 59 02 FF 01 06 00",
                "1: 08 15 71 00 09 14 00",
                "2: 00 0A AA AA AA AA AA",
            )
        )
        val codes = (reply as ModuleReply.Codes).dtcs.map { it.full }
        assertEquals(listOf("P0106-00", "P1571-00", "P1400-00"), codes)
    }

    /** Та же добивка, но в одном кадре - ловится и без длины. */
    @Test
    fun paddingInsideASingleFrameIsNotACode() {
        val reply = ModuleScan.parse(frames("5902FF01060008AAAAAAAA"))
        assertEquals(listOf("P0106-00"), (reply as ModuleReply.Codes).dtcs.map { it.full })
    }

    /**
     * Один код - одна строка.
     *
     * В выдаче с живой машины P1571-00 стоял трижды: один раз красным, два
     * раза жёлтым. Для человека это три разные неисправности, хотя код один.
     */
    @Test
    fun theSameCodeIsListedOnce() {
        val reply = ModuleScan.parse(frames("5902FF01060008010600010106000A"))
        val dtcs = (reply as ModuleReply.Codes).dtcs
        assertEquals(1, dtcs.size)
        assertEquals("P0106-00", dtcs.single().full)
        assertEquals("статусы сложились", true, dtcs.single().active)
        assertEquals(true, dtcs.single().confirmed)
    }

    /**
     * Ответили два блока - читаем первый, а не склейку.
     *
     * Фильтр приёма на переборе стоит на весь диапазон 0x7XX, и на один
     * запрос может ответить несколько блоков. Склеенные встык сообщения
     * читаются как продолжение кодов первого блока - получаются коды из
     * чужих байтов.
     */
    @Test
    fun twoAnsweringModulesAreNotGluedTogether() {
        val raw = frames(
            "00B",
            "0: 59 02 FF 01 06 00",
            "1: 08 15 71 00 09 AA AA",
            "00B",
            "0: 59 02 FF 14 00 00",
            "1: 0A 01 06 00 08 AA AA",
        )
        assertEquals("два отдельных сообщения", 2, raw.size)
        val codes = (ModuleScan.parse(raw) as ModuleReply.Codes).dtcs.map { it.full }
        assertEquals(listOf("P0106-00", "P1571-00"), codes)
    }

    /**
     * Третий байт кода пишется только когда ему есть что сказать.
     *
     * 00 означает "уточнения нет". Строка про обрыв и замыкание под каждым из
     * двадцати двух кодов не объясняла ничего, а список кодов заслоняла.
     */
    @Test
    fun theFailureByteSpeaksOnlyWhenItKnowsSomething() {
        val was = ObdText.russian
        ObdText.russian = true
        try {
            val codes = (ModuleScan.parse(frames("5902FF010600084031130C")) as ModuleReply.Codes).dtcs
            assertNull("00 - это молчание", codes.first { it.code == "P0106" }.failureText)
            val open = codes.first { it.code == "C0031" }
            assertTrue("13 - это обрыв: ${open.failureText}", open.failureText!!.contains("обрыв"))
        } finally {
            ObdText.russian = was
        }
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

        // Блок двигателя отвечает и по UDS. Проверяем на нём сразу две вещи,
        // ради которых и городился разбор: добивка кадра не стала кодом, а
        // повторённый код остался одной строкой.
        val engine = byAddress[0x7E0]
        assertNotNull("блок двигателя найден: ${found.map { it.hex }}", engine)
        assertEquals(
            listOf("P0106-11", "P0113-13", "P0130-29"),
            engine!!.dtcs.map { it.full }
        )

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
