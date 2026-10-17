package com.obdscanner.core

import com.obdscanner.core.dtc.ArchivedModuleDtc
import com.obdscanner.core.dtc.DtcArchive
import com.obdscanner.core.dtc.DtcSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Архив прочитанных ошибок.
 *
 * Коды читают в гараже, а разбираются с ними дома - значит список обязан
 * пережить отключение адаптера и перезапуск приложения.
 */
class DtcArchiveTest {

    private val sample = DtcSnapshot(
        timeMillis = 1_700_000_000_000,
        vehicle = "УАЗ Патриот 2016+",
        vin = "XTT316300H1000001",
        stored = listOf("P0301", "P1513"),
        pending = listOf("P0133"),
        permanent = listOf("P0420"),
        modules = listOf(ArchivedModuleDtc(0x760, "C0031-11")),
        milOn = true,
    )

    @Test
    fun snapshotSurvivesEncodingAndBack() {
        val back = DtcArchive.decode(DtcArchive.encode(listOf(sample)))
        assertEquals(1, back.size)
        assertEquals(sample, back.first())
    }

    @Test
    fun severalSnapshotsKeepTheirOrder() {
        val older = sample.copy(timeMillis = 1_600_000_000_000, stored = listOf("P0171"))
        val back = DtcArchive.decode(DtcArchive.encode(listOf(sample, older)))
        assertEquals(listOf(1_700_000_000_000, 1_600_000_000_000), back.map { it.timeMillis })
        assertEquals(listOf("P0171"), back[1].stored)
    }

    /** Машина без VIN и без единой ошибки - тоже запись, и она не должна рассыпаться. */
    @Test
    fun emptySnapshotIsReadableBack() {
        val empty = DtcSnapshot(timeMillis = 42, vehicle = "Без имени")
        val back = DtcArchive.decode(DtcArchive.encode(listOf(empty))).single()
        assertEquals(42, back.timeMillis)
        assertNull(back.vin)
        assertTrue(back.isEmpty)
    }

    /**
     * Архив, записанный другой версией, не должен ронять разбор: непонятная
     * строка пропускается, остальное читается.
     */
    @Test
    fun unknownLinesAreSkipped() {
        val text = """
            =1700000000000${'\t'}УАЗ${'\t'}${'\t'}0
            S${'\t'}P0301
            X${'\t'}что-то из будущего
            невнятная строка без разделителей
            M${'\t'}760${'\t'}C0031-11
        """.trimIndent()
        val back = DtcArchive.decode(text).single()
        assertEquals(listOf("P0301"), back.stored)
        assertEquals(1, back.modules.size)
        assertEquals(0x760, back.modules.first().address)
    }

    /** Одно подключение - одна запись: обход блоков дополняет её, а не плодит вторую. */
    @Test
    fun secondReadOfTheSameSessionReplacesTheFirst() {
        val engineOnly = sample.copy(modules = emptyList())
        var archive = DtcArchive.merge(emptyList(), engineOnly)
        archive = DtcArchive.merge(archive, sample)
        assertEquals(1, archive.size)
        assertEquals(1, archive.single().modules.size)
    }

    /** Новое чтение встаёт первым, старое остаётся ниже. */
    @Test
    fun newReadGoesOnTop() {
        val older = sample.copy(timeMillis = 1_000)
        val newer = sample.copy(timeMillis = 2_000, stored = listOf("P0420"))
        val archive = DtcArchive.merge(DtcArchive.merge(emptyList(), older), newer)
        assertEquals(listOf(2_000L, 1_000L), archive.map { it.timeMillis })
    }

    /** Архив не растёт бесконечно - иначе в нём не найти нужное чтение. */
    @Test
    fun archiveIsCapped() {
        var archive = emptyList<DtcSnapshot>()
        repeat(DtcArchive.MAX_SNAPSHOTS + 5) { i ->
            archive = DtcArchive.merge(archive, sample.copy(timeMillis = i + 1L))
        }
        assertEquals(DtcArchive.MAX_SNAPSHOTS, archive.size)
        // Остались самые свежие.
        assertEquals((DtcArchive.MAX_SNAPSHOTS + 5).toLong(), archive.first().timeMillis)
    }

    /** Пустое чтение в архив не пишется: оно ничего не говорит. */
    @Test
    fun emptyReadIsNotArchived() {
        val archive = DtcArchive.merge(emptyList(), DtcSnapshot(timeMillis = 1, vehicle = "УАЗ"))
        assertTrue(archive.isEmpty())
    }

    /** Текст для отправки читается без приложения - в этом весь смысл. */
    @Test
    fun textForSharingHasEverything() {
        val text = DtcArchive.asText(sample, "13.10.2026 18:46")
        assertTrue(text, text.contains("P0301"))
        assertTrue(text, text.contains("P0133"))
        assertTrue(text, text.contains("P0420"))
        assertTrue(text, text.contains("C0031-11"))
        assertTrue(text, text.contains("760"))
        assertTrue(text, text.contains("13.10.2026"))
        assertTrue(text, text.contains("XTT316300H1000001"))
    }

    /** Чтение с настоящего Патриота: блок двигателя по UDS, коды со статусом. */
    private val beforeClear = DtcSnapshot(
        timeMillis = 1_760_673_540_000,
        vehicle = "УАЗ Патриот 2016+",
        stored = listOf("P0500"),
        modules = listOf(
            ArchivedModuleDtc(0x7E0, "P0500-00", 0x09),
            ArchivedModuleDtc(0x7E0, "P0112-00", 0x50),
        ),
        descriptions = mapOf("P0500" to "Датчик скорости", "P0112" to "Датчик температуры воздуха"),
        beforeClear = true,
    )

    /** Расшифровка, статус и пометка "перед сбросом" переживают запись в файл. */
    @Test
    fun descriptionsStatusAndKindSurvive() {
        val back = DtcArchive.decode(DtcArchive.encode(listOf(beforeClear))).single()
        assertEquals(beforeClear, back)
        assertTrue(back.beforeClear)
        assertEquals(0x09, back.modules.first().status)
        assertEquals("Датчик температуры воздуха", back.descriptions["P0112"])
    }

    /** Файл, записанный прошлой версией (без новых столбцов), читается как раньше. */
    @Test
    fun archiveOfThePreviousVersionStillReads() {
        val old = "=1760673540000\tУАЗ Патриот 2016+\t\t0\nM\t7E0\tP0500-00\nS\tP0301\n"
        val back = DtcArchive.decode(old).single()
        assertEquals(listOf("P0301"), back.stored)
        assertEquals("P0500-00", back.modules.single().code)
        assertNull(back.modules.single().status)
        assertTrue(back.descriptions.isEmpty())
        assertFalse(back.beforeClear)
        // Расшифровки в такой записи нет - но показать её всё равно есть чем.
        assertTrue(back.describe("P0500-00"), back.describe("P0500-00").contains("скорости"))
    }

    /**
     * Текст для Telegram: у каждого кода расшифровка и статус. Голый
     * "Блок 7E0: P0500-00" ничего не говорил - ошибку спидометра в таком
     * списке было не узнать.
     */
    @Test
    fun sharedTextCarriesDescriptions() {
        val text = DtcArchive.asText(beforeClear, "17.10.2026 09:02")
        assertTrue(text, text.contains("перед сбросом"))
        assertTrue(text, text.contains("Блок 7E0 (двигатель)"))
        assertTrue(text, text.lines().any { it.startsWith("P0500-00 — ") && it.contains("скорости") })
        assertTrue(text, text.contains("ошибка есть сейчас"))
        assertTrue(text, text.contains("тест не проходил после сброса"))
        assertTrue(text, text.lines().any { it.startsWith("P0500 — ") })
    }

    /** Точная расшифровка справочника важнее сохранённой, сохранённая - важнее семейства. */
    @Test
    fun savedDescriptionFillsWhatTheCatalogDoesNotKnow() {
        val snapshot = beforeClear.copy(descriptions = mapOf("P1999" to "Из руководства", "P0500" to "старый текст"))
        assertEquals("Из руководства", snapshot.describe("P1999-00"))
        assertTrue(snapshot.describe("P0500"), snapshot.describe("P0500").contains("скорости"))
    }

    /** В момент чтения в снимок ложатся расшифровки всех его кодов. */
    @Test
    fun descriptionsAreFilledOnRead() {
        val filled = DtcArchive.withDescriptions(beforeClear.copy(descriptions = emptyMap()))
        assertEquals(setOf("P0500", "P0112"), filled.descriptions.keys)
        assertTrue(filled.descriptions.values.all { it.isNotBlank() })
    }

    /**
     * Снимок перед сбросом встаёт на место записи сеанса, а не рядом с ней:
     * иначе одни и те же коды висят в архиве дважды.
     */
    @Test
    fun snapshotBeforeClearReplacesTheSessionRecord() {
        val session = sample.copy(timeMillis = 1_000)
        var archive = DtcArchive.merge(emptyList(), session)
        archive = DtcArchive.merge(archive, beforeClear.copy(timeMillis = 2_000), replacing = 1_000)
        assertEquals(listOf(2_000L), archive.map { it.timeMillis })
        assertTrue(archive.single().beforeClear)
    }

    /** Пустой снимок перед сбросом запись сеанса не стирает. */
    @Test
    fun emptySnapshotBeforeClearKeepsTheSessionRecord() {
        val session = sample.copy(timeMillis = 1_000)
        val archive = DtcArchive.merge(
            listOf(session),
            DtcSnapshot(timeMillis = 2_000, vehicle = "УАЗ", beforeClear = true),
            replacing = 1_000,
        )
        assertEquals(listOf(1_000L), archive.map { it.timeMillis })
    }

    /** Табуляция в названии машины не должна разваливать формат. */
    @Test
    fun tabsInTextDoNotBreakTheFormat() {
        val messy = sample.copy(vehicle = "УАЗ\tПатриот\nвторая строка")
        val back = DtcArchive.decode(DtcArchive.encode(listOf(messy))).single()
        assertEquals("УАЗ Патриот вторая строка", back.vehicle)
        assertEquals(sample.stored, back.stored)
    }
}
