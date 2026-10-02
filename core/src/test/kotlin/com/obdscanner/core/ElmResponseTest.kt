package com.obdscanner.core

import com.obdscanner.core.elm.ElmError
import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.elm.ObdPayload
import com.obdscanner.core.elm.ObdProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор ответов адаптера. Все примеры - в том виде, в каком их печатает
 * ELM327 живьём: с эхом команды, приглашением '>', служебным "SEARCHING..." и
 * разными режимами пробелов и заголовков.
 */
class ElmResponseTest {

    private fun data(raw: String, service: Int, pid: Int?, expected: Int? = null) =
        ObdPayload.data(ElmReply.parse(raw), service, pid, expected)

    @Test
    /** простой ответ с пробелами. */
    fun singleFrameWithSpaces() {
        assertArrayEquals(intArrayOf(0x1A, 0xF8), data("41 0C 1A F8\r\r>", 0x01, 0x0C, 2))
    }

    @Test
    /** ответ без пробелов после ATS0. */
    fun singleFrameWithoutSpaces() {
        assertArrayEquals(intArrayOf(0x7B), data("41057B\r\r>", 0x01, 0x05, 1))
    }

    @Test
    /** эхо команды и SEARCHING не мешают. */
    fun echoAndSearchingAreIgnored() {
        val raw = "010C\rSEARCHING...\r41 0C 0F A0 \r\r>"
        assertArrayEquals(intArrayOf(0x0F, 0xA0), data(raw, 0x01, 0x0C, 2))
    }

    @Test
    /** заголовки включены - CAN-ID и байт длины отбрасываются. */
    fun canHeaderAndPciAreStripped() {
        assertArrayEquals(intArrayOf(0x7B), data("7E8 03 41 05 7B\r\r>", 0x01, 0x05, 1))
    }

    @Test
    /** 29-битный заголовок тоже отбрасывается. */
    fun extendedCanHeaderIsStripped() {
        assertArrayEquals(intArrayOf(0x45), data("18DAF110 03 41 05 45\r\r>", 0x01, 0x05, 1))
    }

    @Test
    /** лишние байты-заполнители обрезаются по длине PID. */
    fun paddingBytesAreTrimmedToPidLength() {
        // Машина добила кадр нулями до восьми байт - в значение они попасть не должны.
        assertArrayEquals(intArrayOf(0x1A, 0xF8), data("41 0C 1A F8 00 00 00\r\r>", 0x01, 0x0C, 2))
    }

    @Test
    /** многокадровый ответ с нумерацией строк склеивается. */
    fun segmentedMultilineResponseIsMerged() {
        val raw = "014\r0: 49 02 01 31 44 34\r1: 47 50 30 30 52 35 35\r2: 42 31 32 33 34 35 36\r\r>"
        val payload = data(raw, 0x09, 0x02)!!
        assertEquals(18, payload.size) // счётчик 0x01 + 17 символов VIN
        assertEquals(0x01, payload[0])
        assertEquals('1'.code, payload[1])
    }

    @Test
    /** ISO-TP с включёнными заголовками собирается сам. */
    fun isoTpFramesAreReassembled() {
        // Первый кадр (10 14 = длина 0x14) и два продолжения (21, 22).
        // Первый кадр несёт 2 байта служебных и 6 данных, продолжения - 1 и 7.
        val raw = "7E8 10 14 49 02 01 31 44 34\r" +
            "7E8 21 47 50 30 30 52 35 35\r" +
            "7E8 22 42 31 32 33 34 35 36\r\r>"
        val payload = data(raw, 0x09, 0x02)!!
        assertEquals(18, payload.size)
        assertEquals("1D4GP00R55B123456", payload.drop(1).map { it.toChar() }.joinToString(""))
    }

    @Test
    /** NO DATA это ошибка, а не пустые данные. */
    fun noDataIsAnErrorNotEmptyData() {
        val reply = ElmReply.parse("NO DATA\r\r>")
        assertEquals(ElmError.NO_DATA, reply.error)
        assertNull(ObdPayload.data(reply, 0x01, 0x05))
    }

    @Test
    /** UNABLE TO CONNECT отличается от CAN ERROR. */
    fun errorKindsAreDistinguished() {
        assertEquals(ElmError.UNABLE_TO_CONNECT, ElmReply.parse("UNABLE TO CONNECT\r\r>").error)
        assertEquals(ElmError.CAN_ERROR, ElmReply.parse("CAN ERROR\r\r>").error)
        assertEquals(ElmError.BUFFER_FULL, ElmReply.parse("BUFFER FULL\r\r>").error)
        assertEquals(ElmError.STOPPED, ElmReply.parse("STOPPED\r\r>").error)
    }

    @Test
    /** вопросительный знак - адаптер не понял команду. */
    fun questionMarkMeansUnsupportedCommand() {
        assertEquals(ElmError.UNSUPPORTED_COMMAND, ElmReply.parse("ATXYZ\r?\r\r>", echo = "ATXYZ").error)
    }

    @Test
    /** OK распознаётся для AT-команд. */
    fun okIsRecognisedForAtCommands() {
        assertTrue(ElmReply.parse("ATE0\rOK\r\r>", echo = "ATE0").isOk)
    }

    @Test
    /** напряжение адаптера читается из ATRV. */
    fun voltageLineIsReadable() {
        assertEquals("13.9V", ElmReply.parse("13.9V\r\r>").firstLine)
    }

    @Test
    /** чужой PID в ответе не принимается за свой. */
    fun responseForAnotherPidIsRejected() {
        // Ответ на 010C, а спрашиваем 010D - данных быть не должно.
        assertNull(data("41 0C 1A F8\r\r>", 0x01, 0x0D, 1))
    }

    @Test
    /** отрицательный ответ ЭБУ распознаётся. */
    fun negativeResponseIsDetected() {
        val reply = ElmReply.parse("7F 01 12\r\r>")
        assertTrue(ObdPayload.isNegative(reply, 0x01))
        assertNull(ObdPayload.data(reply, 0x01, 0x05))
    }

    @Test
    /** протокол из ATDPN. */
    fun protocolFromAtDpn() {
        assertEquals(ObdProtocol.ISO15765_11_500, ObdProtocol.fromDpn("6"))
        assertEquals(ObdProtocol.ISO15765_11_500, ObdProtocol.fromDpn("A6"))
        assertEquals(ObdProtocol.ISO9141_2, ObdProtocol.fromDpn(" 3 \r"))
        assertNull(ObdProtocol.fromDpn(""))
    }

    @Test
    /** несколько ЭБУ на шине дают несколько кадров. */
    fun multipleEcusProduceMultipleFrames() {
        val raw = "7E8 03 41 05 7B\r7E9 03 41 05 7A\r\r>"
        val frames = ObdPayload.frames(ElmReply.parse(raw))
        assertEquals(2, frames.size)
        // Берём первый - он от основного блока управления двигателем.
        assertArrayEquals(intArrayOf(0x7B), data(raw, 0x01, 0x05, 1))
    }
}
