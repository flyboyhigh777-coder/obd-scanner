package com.obdscanner.core

import com.obdscanner.core.obd.VinDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор VIN: чистка ответа шины, страна, год, контрольный разряд. */
class VinDecoderTest {

    @Test
    /** VIN вычищается из ответа с счётчиком и нулями. */
    fun vinIsExtractedFromBusResponse() {
        // 0x01 - счётчик элементов, нули - добивка кадра.
        val data = intArrayOf(0x01) + "1D4GP00R55B123456".map { it.code }.toIntArray() + intArrayOf(0, 0, 0)
        assertEquals("1D4GP00R55B123456", VinDecoder.fromBytes(data))
    }

    @Test
    /** год выпуска по десятому символу. */
    fun modelYearFromTenthCharacter() {
        // Не Северная Америка - берём текущий цикл: "G" это 2016.
        assertEquals(2016, VinDecoder.modelYear("XTA210990G1234567", currentYear = 2026))
        // VIN из США с цифрой на 7-й позиции - старый цикл: "Y" это 2000.
        assertEquals(2000, VinDecoder.modelYear("1G1JC5242Y7123456", currentYear = 2026))
        // Тот же код года, но 7-я позиция буква - цикл 2010+.
        assertEquals(2030, VinDecoder.modelYear("1G1JC5A24Y7123456", currentYear = 2035))
        // Год из будущего невозможен - значит это предыдущий цикл.
        assertEquals(2000, VinDecoder.modelYear("1G1JC5A24Y7123456", currentYear = 2026))
    }

    @Test
    /** контрольный разряд проверяется только там, где он обязателен. */
    fun checkDigitOnlyWhereRequired() {
        // Эталон из стандарта: 11111111111111111 даёт контрольный разряд 1.
        assertEquals(true, VinDecoder.checkDigit("11111111111111111"))
        // Испорченный разряд ловится.
        assertEquals(false, VinDecoder.checkDigit("11111111211111111"))
        // Европа и Россия контрольный разряд не обязаны ставить - "не применимо".
        assertNull(VinDecoder.checkDigit("XTA210990G1234567"))
        assertNull(VinDecoder.checkDigit("WVWZZZ1KZAW123456"))
    }

    @Test
    /** страна и производитель по WMI. */
    fun countryAndManufacturerFromWmi() {
        val lada = VinDecoder.decode("XTA210990G1234567")!!
        assertEquals("XTA", lada.wmi)
        assertTrue(lada.country!!.contains("Росси"))
        assertTrue(lada.manufacturer!!.contains("LADA"))

        val vw = VinDecoder.decode("WVWZZZ1KZAW123456")!!
        assertTrue(vw.country!!.contains("Герман"))
        assertEquals("Volkswagen", vw.manufacturer)

        val toyota = VinDecoder.decode("JTDKB20U703123456")!!
        assertTrue(toyota.country!!.contains("Япони"))
        assertEquals("Toyota", toyota.manufacturer)

        // Нидерланды - это XL..XR, а не Россия, хотя буква та же.
        assertTrue(VinDecoder.decode("XLRTE47MS0E123456")!!.country!!.contains("Нидерл"))
    }

    @Test
    /** неизвестный WMI не выдумывается. */
    fun unknownWmiIsNotInvented() {
        val info = VinDecoder.decode("ABC12345678901234")!!
        assertNull(info.manufacturer)
        assertEquals("ABC", info.wmi)
    }

    @Test
    /** мусор вместо VIN отвергается. */
    fun garbageVinIsRejected() {
        assertNull(VinDecoder.decode("КОРОТКО"))
        assertNull(VinDecoder.decode(""))
        // Буквы I, O, Q в VIN не используются.
        assertNull(VinDecoder.decode("IOQ12345678901234"))
        // 16 символов - не VIN.
        assertNull(VinDecoder.decode("1D4GP00R55B12345"))
    }

    @Test
    /** серийный номер - последние шесть символов. */
    fun serialIsLastSixCharacters() {
        assertEquals("234567", VinDecoder.decode("XTA210990G1234567")!!.serial)
    }
}
