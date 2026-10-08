package com.obdscanner.core

import com.obdscanner.core.dtc.Dtc
import com.obdscanner.core.dtc.DtcCatalog
import com.obdscanner.core.dtc.DtcReference
import com.obdscanner.core.dtc.DtcTextSource
import com.obdscanner.core.dtc.ManufacturerDtc
import com.obdscanner.core.dtc.ManufacturerDtcFamily
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поиск расшифровки идёт слоями: свой русский справочник, таблица блока
 * управления машины, обобщённый датасет SAE, описание семейства. Проверяем, что
 * слои не перебивают друг друга и что источник текста виден.
 */
class ManufacturerDtcTest {

    @After
    fun tearDown() {
        DtcCatalog.manufacturerFamily = null
        DtcCatalog.reference = null
    }

    /** Без выбранного ЭБУ код производителя остаётся без точной расшифровки. */
    @Test
    fun withoutEcuManufacturerCodeStaysUnknown() {
        val dtc = Dtc("P1570")
        assertFalse(DtcCatalog.hasExactDescription("P1570"))
        assertEquals(DtcTextSource.FAMILY, dtc.descriptionDetails.source)
        assertTrue(dtc.description.contains("производител"))
    }

    /** С выбранным ME17.9.7 тот же код расшифровывается. */
    @Test
    fun ecuTableResolvesManufacturerCode() {
        DtcCatalog.manufacturerFamily = ManufacturerDtcFamily.ME17_9_7
        val dtc = Dtc("P1570")
        val details = dtc.descriptionDetails
        assertEquals(DtcTextSource.MANUFACTURER, details.source)
        assertTrue(details.text, details.text.contains("иммобилизатор", ignoreCase = true))
        // Источник подписан - видно, что это не стандарт
        assertNotNull(details.sourceNote)
        assertTrue(details.sourceNote!!.contains("ME17.9.7"))
        assertTrue(DtcCatalog.hasExactDescription("P1570"))
    }

    /** Коды регулятора холостого хода и реле бензонасоса - самые частые на ЗМЗ. */
    @Test
    fun commonZmzCodes() {
        DtcCatalog.manufacturerFamily = ManufacturerDtcFamily.ME17_9_7
        assertTrue(Dtc("P1513").description.contains("холостого хода"))
        assertTrue(Dtc("P1500").description.contains("бензонасос"))
        assertTrue(Dtc("P1606").description.contains("неровной дороги"))
        assertTrue(Dtc("P1301").description.contains("цилиндре 1"))
        assertEquals(38, ManufacturerDtc.size(ManufacturerDtcFamily.ME17_9_7))
    }

    /** Русский справочник важнее таблицы ЭБУ и датасета. */
    @Test
    fun curatedWinsOverEverything() {
        DtcCatalog.manufacturerFamily = ManufacturerDtcFamily.ME17_9_7
        DtcCatalog.reference = { DtcReference("Из справочника", "English text") }
        val details = Dtc("P0301").descriptionDetails
        assertEquals(DtcTextSource.CURATED, details.source)
        assertTrue(details.text.contains("цилиндр"))
    }

    /** Справочник подхватывает обобщённый код, которого нет в русской таблице. */
    @Test
    fun referenceFillsTheGap() {
        val before = Dtc("P0479").descriptionDetails
        assertEquals(DtcTextSource.FAMILY, before.source)

        DtcCatalog.reference = { code ->
            if (code == "P0479") {
                DtcReference(
                    "Клапан управления давлением выхлопа - характеристика",
                    "Exhaust Pressure Control Valve Performance",
                    causes = listOf("Заклинил клапан"),
                    symptoms = listOf("Горит Check Engine"),
                )
            } else {
                null
            }
        }
        val after = Dtc("P0479").descriptionDetails
        assertEquals(DtcTextSource.SAE_REFERENCE, after.source)
        assertEquals("Клапан управления давлением выхлопа - характеристика", after.text)
        assertEquals(listOf("Заклинил клапан"), after.causes)
        assertEquals(listOf("Горит Check Engine"), after.symptoms)
        // Приложение должно иметь возможность сказать, откуда текст
        assertNotNull(after.sourceNote)
    }

    /**
     * Причины из справочника видны и тогда, когда название взято слоем выше:
     * у русской таблицы текст точнее, но причин в ней нет.
     */
    @Test
    fun curatedTitleKeepsReferenceCauses() {
        DtcCatalog.reference = {
            DtcReference("не нужно", "not used", causes = listOf("Отказала катушка"))
        }
        val details = Dtc("P0301").descriptionDetails
        assertEquals(DtcTextSource.CURATED, details.source)
        assertEquals(listOf("Отказала катушка"), details.causes)
    }

    /** Код производителя, которого нет и в таблице ЭБУ, не подменяется похожим. */
    @Test
    fun unknownManufacturerCodeIsNotInvented() {
        DtcCatalog.manufacturerFamily = ManufacturerDtcFamily.ME17_9_7
        DtcCatalog.reference = { null }
        val details = Dtc("P1999").descriptionDetails
        assertEquals(DtcTextSource.FAMILY, details.source)
        assertTrue(details.text.contains("производител"))
    }
}
