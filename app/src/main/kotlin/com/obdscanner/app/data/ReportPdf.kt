package com.obdscanner.app.data

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.obdscanner.core.diag.ReportBlock
import com.obdscanner.core.diag.ReportGeometry
import com.obdscanner.core.diag.ReportLayout
import com.obdscanner.core.diag.ReportTextRole
import com.obdscanner.core.diag.ReportTone
import java.io.OutputStream

/**
 * Отчёт о диагностике страницами PDF.
 *
 * Почему PDF, а не текстовый файл: текст на телефоне открывать нечем. В
 * Android нет системного просмотрщика .txt, и файл, отправленный в мессенджер,
 * человек открывает браузером - а тот для локального файла угадывает кодировку
 * и для кириллицы угадывает неверно. PDF открывается всем и везде, выглядит
 * одинаково у отправителя и у получателя и печатается без возни.
 *
 * Рисуется средствами самой платформы ([PdfDocument]): ни одной библиотеки, ни
 * одного лишнего мегабайта в сборке и никаких забот о шрифтах с кириллицей -
 * берутся системные.
 *
 * Здесь только рисование. Раскладка - перенос строк и разрывы страниц, то
 * есть всё, где можно ошибиться, - живёт в [ReportLayout] в :core и проверяется
 * обычными тестами, без телефона и без эмулятора.
 */
object ReportPdf {

    private const val COLOR_TEXT = 0xFF1A1A1A.toInt()
    private const val COLOR_MUTED = 0xFF6B6B6B.toInt()
    private const val COLOR_GOOD = 0xFF1B7A3E.toInt()
    private const val COLOR_WARN = 0xFF9A6400.toInt()
    private const val COLOR_BAD = 0xFFB3261E.toInt()
    private const val COLOR_RULE = 0xFFD0D0D0.toInt()

    /**
     * Цвет строки: сначала смысл (красная строка в документе, который смотрят
     * пять секунд, находится взглядом, а не чтением), потом роль.
     */
    private fun color(role: ReportTextRole, tone: ReportTone): Int = when (tone) {
        ReportTone.GOOD -> COLOR_GOOD
        ReportTone.WARN -> COLOR_WARN
        ReportTone.BAD -> COLOR_BAD
        ReportTone.PLAIN -> when (role) {
            ReportTextRole.LABEL, ReportTextRole.NOTE, ReportTextRole.SUBTITLE, ReportTextRole.FOOTER -> COLOR_MUTED
            else -> COLOR_TEXT
        }
    }

    fun write(blocks: List<ReportBlock>, footer: String, out: OutputStream) {
        val geometry = ReportGeometry()
        val paints = HashMap<Pair<Float, Boolean>, Paint>()
        fun paintFor(size: Float, bold: Boolean): Paint = paints.getOrPut(size to bold) {
            Paint().apply {
                isAntiAlias = true
                textSize = size
                typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        val layout = ReportLayout(geometry, footer) { text, size, bold ->
            paintFor(size, bold).measureText(text)
        }
        val rulePaint = Paint().apply { color = COLOR_RULE; strokeWidth = 0.7f }

        val document = PdfDocument()
        try {
            for (page in layout.paginate(blocks)) {
                val info = PdfDocument.PageInfo.Builder(
                    geometry.width.toInt(),
                    geometry.height.toInt(),
                    page.number
                ).create()
                val started = document.startPage(info)
                val canvas = started.canvas
                page.rules.forEach { canvas.drawLine(it.left, it.y, it.right, it.y, rulePaint) }
                page.texts.forEach { draw ->
                    val paint = paintFor(layout.size(draw.role), layout.isBold(draw.role))
                    paint.color = color(draw.role, draw.tone)
                    canvas.drawText(draw.text, draw.x, draw.baseline, paint)
                }
                document.finishPage(started)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }
}
