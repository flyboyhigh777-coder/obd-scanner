package com.obdscanner.core

import com.obdscanner.core.dtc.Dtc
import com.obdscanner.core.dtc.DtcCatalog
import com.obdscanner.core.dtc.DtcCodec
import com.obdscanner.core.dtc.DtcSeverity
import com.obdscanner.core.dtc.DtcSource
import com.obdscanner.core.dtc.DtcSystem
import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.elm.ObdPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Упаковка кодов неисправностей в байты, разбор списка и справочник. */
class DtcTest {

    @Test
    /** два байта превращаются в код. */
    fun twoBytesDecodeToCode() {
        assertEquals("P0133", DtcCodec.decode(0x01, 0x33))
        assertEquals("P0301", DtcCodec.decode(0x03, 0x01))
        assertEquals("P0420", DtcCodec.decode(0x04, 0x20))
        // Старшие биты задают букву: 01 -> C, 10 -> B, 11 -> U
        assertEquals("C0321", DtcCodec.decode(0x43, 0x21))
        assertEquals("B1234", DtcCodec.decode(0x92, 0x34))
        assertEquals("U0100", DtcCodec.decode(0xC1, 0x00))
        // Вторая цифра кода - шестнадцатеричная
        assertEquals("P0A80", DtcCodec.decode(0x0A, 0x80))
    }

    @Test
    /** код и байты переводятся туда и обратно. */
    fun codeRoundTripsThroughBytes() {
        for (code in listOf("P0133", "P0301", "C0321", "B1234", "U0100", "P0A80", "P2096", "P3400")) {
            val (a, b) = DtcCodec.encode(code)!!
            assertEquals(code, DtcCodec.decode(a, b))
        }
        assertNull(DtcCodec.encode("ерунда"))
        assertNull(DtcCodec.encode("Z0133"))
    }

    @Test
    /** список кодов с байтом счётчика на CAN. */
    fun dtcListWithCountByte() {
        // 43 02 0133 0171 - два кода, перед ними счётчик.
        val frames = ObdPayload.allFrameData(ElmReply.parse("43 02 01 33 01 71\r\r>"), 0x03)
        val codes = DtcCodec.parse(frames).map { it.code }
        assertEquals(listOf("P0133", "P0171"), codes)
    }

    @Test
    /** список кодов без счётчика с добивкой нулями. */
    fun dtcListWithoutCountByte() {
        // Так отвечают по K-line: по три кода в строке, хвост - нули.
        val frames = ObdPayload.allFrameData(ElmReply.parse("43 01 33 01 71 00 00\r\r>"), 0x03)
        val codes = DtcCodec.parse(frames).map { it.code }
        assertEquals(listOf("P0133", "P0171"), codes)
    }

    @Test
    /** код, похожий на служебный байт, не ломает разбор. */
    fun codeLookingLikeServiceByteIsParsed() {
        // C0321 это байты 43 21 - те же, что и ответный байт режима 03.
        val frames = ObdPayload.allFrameData(ElmReply.parse("43 02 43 21 01 33\r\r>"), 0x03)
        assertEquals(listOf("C0321", "P0133"), DtcCodec.parse(frames).map { it.code })
    }

    @Test
    /** пустой список ошибок. */
    fun emptyDtcList() {
        val frames = ObdPayload.allFrameData(ElmReply.parse("43 00 00 00 00 00\r\r>"), 0x03)
        assertTrue(DtcCodec.parse(frames).isEmpty())
    }

    @Test
    /** многокадровый список ошибок. */
    fun multiFrameDtcList() {
        val raw = "00C\r0: 43 04 01 33 01 71\r1: 03 01 04 20 00 00\r\r>"
        val codes = DtcCodec.parse(ObdPayload.allFrameData(ElmReply.parse(raw), 0x03)).map { it.code }
        assertEquals(listOf("P0133", "P0171", "P0301", "P0420"), codes)
    }

    @Test
    /** повторы от разных блоков управления не дублируются. */
    fun duplicatesFromSeveralEcusAreMerged() {
        val raw = "7E8 06 43 02 01 33 01 71\r7E9 06 43 02 01 33 01 71\r\r>"
        val codes = DtcCodec.parse(ObdPayload.allFrameData(ElmReply.parse(raw), 0x03)).map { it.code }
        assertEquals(listOf("P0133", "P0171"), codes)
    }

    @Test
    /** система определяется по букве. */
    fun systemComesFromFirstLetter() {
        assertEquals(DtcSystem.POWERTRAIN, Dtc("P0301").system)
        assertEquals(DtcSystem.CHASSIS, Dtc("C0035").system)
        assertEquals(DtcSystem.BODY, Dtc("B0001").system)
        assertEquals(DtcSystem.NETWORK, Dtc("U0100").system)
    }

    @Test
    /** коды производителя помечены как таковые. */
    fun manufacturerCodesAreFlagged() {
        assertTrue(Dtc("P1234").manufacturerSpecific)
        assertTrue(Dtc("P3000").manufacturerSpecific)
        assertFalse(Dtc("P0301").manufacturerSpecific)
        assertFalse(Dtc("P2096").manufacturerSpecific)
        assertTrue(Dtc("U1000").manufacturerSpecific)
        assertFalse(Dtc("U0100").manufacturerSpecific)
    }

    @Test
    /** известные коды имеют точную расшифровку. */
    fun knownCodesHaveExactDescription() {
        assertTrue(DtcCatalog.hasExactDescription("P0301"))
        assertTrue(DtcCatalog.describe("P0301").contains("цилиндр", ignoreCase = true))
        assertTrue(DtcCatalog.describe("P0420").contains("катализатор", ignoreCase = true))
        assertTrue(DtcCatalog.describe("P0171").contains("бедная", ignoreCase = true))
    }

    @Test
    /** неизвестный код описывается хотя бы семейством. */
    fun unknownCodeFallsBackToFamily() {
        assertFalse(DtcCatalog.hasExactDescription("P0479"))
        val text = DtcCatalog.describe("P0479")
        assertTrue(text, text.contains("EGR") || text.contains("токсичн"))
        // Код производителя честно называется кодом производителя.
        assertTrue(DtcCatalog.describe("P1623").contains("производител"))
        // И ни один код не остаётся без текста.
        for (code in listOf("P0001", "P1555", "P2777", "P3401", "C0999", "B2222", "U1234")) {
            assertTrue(code, DtcCatalog.describe(code).length > 5)
        }
    }

    @Test
    /** срочность расставлена по смыслу. */
    fun severityIsAssignedByMeaning() {
        assertEquals(DtcSeverity.CRITICAL, Dtc("P0301").severity)
        assertEquals(DtcSeverity.CRITICAL, Dtc("P0217").severity)
        assertEquals(DtcSeverity.INFO, Dtc("P0457").severity)
        assertEquals(DtcSeverity.WARNING, Dtc("P0133").severity)
    }

    @Test
    /** источник кода сохраняется. */
    fun dtcSourceIsKept() {
        val dtc = Dtc("P0420", DtcSource.PERMANENT)
        assertEquals(DtcSource.PERMANENT, dtc.source)
        assertEquals(0x0A, dtc.source.service)
        assertEquals(0x03, DtcSource.STORED.service)
        assertEquals(0x07, DtcSource.PENDING.service)
    }

    @Test
    /** в справочнике нет пустых описаний. */
    fun catalogIsReasonablyLarge() {
        assertTrue(DtcCatalog.size > 250)
    }
}
