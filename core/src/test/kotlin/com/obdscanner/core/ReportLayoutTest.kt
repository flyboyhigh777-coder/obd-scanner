package com.obdscanner.core

import com.obdscanner.core.diag.QuickCheck
import com.obdscanner.core.diag.ReportBlock
import com.obdscanner.core.diag.ReportGeometry
import com.obdscanner.core.diag.ReportLayout
import com.obdscanner.core.diag.ReportTextRole
import com.obdscanner.core.diag.ReportTone
import com.obdscanner.core.session.ElmAdapter
import com.obdscanner.core.session.ObdSession
import com.obdscanner.core.sim.SimulatedEcu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Раскладка отчёта по страницам.
 *
 * Проверяется то, где ошибаются: переносы и разрывы страниц. Ошибка здесь
 * выглядит как потерянный хвост отчёта или текст, уехавший за край листа, -
 * и на телефоне её замечают последней, уже отправив отчёт в сервис.
 */
class ReportLayoutTest {

    private class FakeClock(var millis: Long = 1_770_000_000_000) {
        fun now(): Long = millis
        fun sleep(ms: Long) { millis += ms }
    }

    /** Ширина строки: моноширинное приближение - для раскладки этого хватает. */
    private val measure: (String, Float, Boolean) -> Float =
        { text, size, bold -> text.length * size * if (bold) 0.58f else 0.52f }

    private val geometry = ReportGeometry()

    private fun layout(footer: String = "Отчёт") = ReportLayout(geometry, footer, measure)

    private fun fullReport(): List<ReportBlock> {
        val clock = FakeClock()
        val ecu = SimulatedEcu(clock = clock::now)
        val session = ObdSession(ElmAdapter(ecu, sleep = clock::sleep, now = clock::now))
        session.connect()
        return QuickCheck(session, now = clock::now).run().blocks()
    }

    @Test
    /** ничего не уезжает за поля страницы. */
    fun nothingLeavesThePage() {
        val pages = layout().paginate(fullReport())
        assertTrue("страницы есть", pages.isNotEmpty())
        for (page in pages) {
            for (draw in page.texts) {
                val width = measure(draw.text, layout().size(draw.role), layout().isBold(draw.role))
                assertTrue("левое поле: '${draw.text}'", draw.x >= geometry.marginLeft - 0.01f)
                assertTrue(
                    "правое поле: '${draw.text}' (${draw.x + width} > ${geometry.width - geometry.marginRight})",
                    draw.x + width <= geometry.width - geometry.marginRight + 0.01f
                )
                assertTrue("верхнее поле: '${draw.text}'", draw.baseline >= geometry.marginTop)
                // Подпись внизу стоит ниже границы текста - это её место.
                if (draw.role != ReportTextRole.FOOTER) {
                    assertTrue(
                        "нижнее поле: '${draw.text}' (${draw.baseline} > ${geometry.bottom})",
                        draw.baseline <= geometry.bottom + 0.01f
                    )
                }
            }
        }
    }

    @Test
    /** ни одна строка отчёта не теряется при разбивке на страницы. */
    fun everyBlockSurvivesPagination() {
        val blocks = fullReport()
        val pages = layout().paginate(blocks)
        val rendered = pages.flatMap { it.texts }.joinToString(" ") { it.text }
        val expected = blocks.mapNotNull {
            when (it) {
                is ReportBlock.Row -> it.label
                is ReportBlock.Section -> it.text
                is ReportBlock.Title -> it.text
                is ReportBlock.Note -> it.text.take(20)
                ReportBlock.Space -> null
            }
        }
        // Слова могут разъехаться по строкам при переносе, поэтому ищем по
        // первому слову - его достаточно, чтобы поймать потерянный блок.
        expected.forEach { text ->
            val probe = text.split(' ').first()
            assertTrue("в отчёте осталось '$probe'", rendered.contains(probe))
        }
    }

    @Test
    /** длинный отчёт разбивается на несколько страниц, и они пронумерованы. */
    fun longReportGetsSeveralNumberedPages() {
        val pages = layout().paginate(fullReport())
        assertTrue("страниц больше одной: ${pages.size}", pages.size >= 2)
        assertEquals((1..pages.size).toList(), pages.map { it.number })
        // Подпись с номером - на каждой странице.
        pages.forEach { page ->
            val footer = page.texts.filter { it.role == ReportTextRole.FOOTER }
            assertEquals("подпись на странице ${page.number}", 1, footer.size)
            assertTrue(footer.first().text.endsWith("· ${page.number}"))
        }
    }

    @Test
    /** слово длиннее строки режется, а не уезжает за край. */
    fun oneLongWordIsCut() {
        val word = "X".repeat(400)
        val pages = layout().paginate(listOf(ReportBlock.Note(word)))
        val pieces = pages.flatMap { it.texts }.filter { it.role == ReportTextRole.NOTE }
        assertTrue("слово разрезано на части: ${pieces.size}", pieces.size > 1)
        assertEquals("и собирается обратно без потерь", word, pieces.joinToString("") { it.text })
    }

    @Test
    /** заголовок раздела не остаётся висеть один в конце страницы. */
    fun sectionTitleIsNotLeftHanging() {
        // Набиваем страницу строками, а затем ставим раздел - он должен уехать
        // на следующую страницу вместе со своей первой строкой.
        val filler = List(60) { ReportBlock.Row("Параметр $it", "значение") }
        val blocks = filler + ReportBlock.Section("Последний раздел") + ReportBlock.Row("После", "раздела")
        val pages = layout().paginate(blocks)
        val sectionPage = pages.first { page -> page.texts.any { it.text == "Последний раздел" } }
        val afterPage = pages.first { page -> page.texts.any { it.text == "После" } }
        assertEquals("раздел и строка под ним на одной странице", sectionPage.number, afterPage.number)
    }

    @Test
    /** цвет строки берётся из отчёта: провалившийся тест помечен, а не покрашен наугад. */
    fun toneSurvivesLayout() {
        val pages = layout().paginate(
            listOf(
                ReportBlock.Row("Вывод", "ВНЕ ПРЕДЕЛОВ", ReportTone.BAD),
                ReportBlock.Row("Готовность", "завершён", ReportTone.GOOD)
            )
        )
        val texts = pages.flatMap { it.texts }
        assertEquals(ReportTone.BAD, texts.first { it.text == "ВНЕ ПРЕДЕЛОВ" }.tone)
        assertEquals(ReportTone.GOOD, texts.first { it.text == "завершён" }.tone)
    }
}
