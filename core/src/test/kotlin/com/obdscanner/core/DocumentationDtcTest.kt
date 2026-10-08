package com.obdscanner.core

import com.obdscanner.core.dtc.Dtc
import com.obdscanner.core.dtc.DtcCatalog
import com.obdscanner.core.dtc.DtcReference
import com.obdscanner.core.dtc.DtcTextSource
import com.obdscanner.core.dtc.ManufacturerDtc
import com.obdscanner.core.dtc.ManufacturerDtcFamily
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Коды из документации на конкретную машину - отдельный, самый надёжный слой.
 *
 * Таблица кладётся текстовым файлом, а не вписывается в код: встроенная сводка по
 * контроллеру собрана по открытым описаниям и неполная, руководство по ремонту у
 * каждого своё, и дописать строку в файл должно быть можно без пересборки
 * приложения и без знания Kotlin.
 */
class DocumentationDtcTest {

    @After
    fun tearDown() {
        // Справочник - глобальный, и тесты идут в одной JVM: не убрать за собой
        // значит сломать соседние тесты.
        ManufacturerDtc.fromDocumentation = emptyMap()
        DtcCatalog.manufacturerFamily = null
        DtcCatalog.reference = null
    }

    /** Разбор файла: табуляция или точка с запятой, комментарии, мусор. */
    @Test
    fun parsesTableFile() {
        val text = listOf(
            "# комментарий",
            "P1513\tЦепь РХХ: замыкание на массу",
            "P1606;Датчик неровной дороги",
            "",
            "это не строка таблицы",
            "XXXXX\tмусор",
            "U0100\tНет связи с ЭБУ\tLost communication"
        ).joinToString("\n")

        val codes = ManufacturerDtc.parseDocumentationTable(text)
        assertEquals(3, codes.size)
        assertEquals("Цепь РХХ: замыкание на массу", codes["P1513"])
        assertEquals("Датчик неровной дороги", codes["P1606"])
        // Лишний столбец игнорируется, а не склеивается с описанием
        assertEquals("Нет связи с ЭБУ", codes["U0100"])
        assertTrue("мусорный код отброшен", "XXXXX" !in codes)
    }

    /** Регистр кода не важен, пробелы по краям обрезаются. */
    @Test
    fun parsingIsForgiving() {
        val codes = ManufacturerDtc.parseDocumentationTable("  p1513 \t  Цепь РХХ  \n")
        assertEquals(mapOf("P1513" to "Цепь РХХ"), codes)
    }

    /** Пустой файл не ломает справочник и ничего не подменяет. */
    @Test
    fun emptyFileChangesNothing() {
        ManufacturerDtc.fromDocumentation = ManufacturerDtc.parseDocumentationTable("# только комментарий\n\n")
        DtcCatalog.manufacturerFamily = ManufacturerDtcFamily.ME17_9_7
        assertEquals(DtcTextSource.MANUFACTURER, Dtc("P1513").descriptionDetails.source)
    }

    /** Документация важнее и сводки по контроллеру, и русского справочника. */
    @Test
    fun documentationWinsOverEverything() {
        DtcCatalog.manufacturerFamily = ManufacturerDtcFamily.ME17_9_7
        DtcCatalog.reference = { DtcReference("Из справочника", "English text") }
        ManufacturerDtc.fromDocumentation = mapOf(
            "P1513" to "Из руководства: цепь РХХ, замыкание на массу",
            "P0301" to "Из руководства: пропуски в первом цилиндре"
        )

        val manufacturerCode = Dtc("P1513").descriptionDetails
        assertEquals(DtcTextSource.DOCUMENTATION, manufacturerCode.source)
        assertTrue(manufacturerCode.text.startsWith("Из руководства"))
        assertTrue(manufacturerCode.sourceNote!!.contains("документации"))

        // Даже для обобщённого кода документация на машину точнее общего текста
        assertEquals(DtcTextSource.DOCUMENTATION, Dtc("P0301").descriptionDetails.source)
        assertTrue(DtcCatalog.hasExactDescription("P1513"))
    }

    /** Кода нет в документации - работают прежние слои, ничего не потерялось. */
    @Test
    fun otherLayersStillWork() {
        DtcCatalog.manufacturerFamily = ManufacturerDtcFamily.ME17_9_7
        ManufacturerDtc.fromDocumentation = mapOf("P1513" to "из руководства")
        assertEquals(DtcTextSource.MANUFACTURER, Dtc("P1570").descriptionDetails.source)
        assertEquals(DtcTextSource.CURATED, Dtc("P0420").descriptionDetails.source)
        assertEquals(DtcTextSource.FAMILY, Dtc("P1999").descriptionDetails.source)
    }
}
