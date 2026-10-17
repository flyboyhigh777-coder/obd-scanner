package com.obdscanner.app.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.content.FileProvider
import com.obdscanner.core.dtc.DtcArchive
import com.obdscanner.core.dtc.DtcSnapshot
import com.obdscanner.core.elm.ObdText
import com.obdscanner.core.modules.UdsStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Архив ошибок в PDF.
 *
 * Текст из "Отправить" хорош для мессенджера, но длинный список в нём
 * неудобно разглядывать, а распечатать или отдать диагносту - тем более. PDF
 * показывает запись целиком: каждый код с расшифровкой и статусом, и
 * открывается на любом телефоне и компьютере без этого приложения.
 *
 * Рисуется средствами самой платформы (PdfDocument + StaticLayout): никаких
 * библиотек, шрифт системный - кириллица в нём есть всегда. Файл ложится во
 * внутренний кеш и отдаётся через FileProvider, разрешений это не требует.
 */
object DtcArchivePdf {

    const val MIME = "application/pdf"

    private const val DIRECTORY = "archive"

    /** A4 в пунктах PDF. */
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 42f
    private const val FOOTER = 22f

    /** Выгрузки старше суток удаляются: кеш не должен расти от каждого нажатия. */
    private const val KEEP_MILLIS = 24L * 60 * 60 * 1000

    /**
     * Пишет PDF и возвращает ссылку для FileProvider; null - не получилось.
     * [dateOf] - как подписывать время записи (тот же формат, что на экране).
     */
    fun write(context: Context, snapshots: List<DtcSnapshot>, dateOf: (Long) -> String): Uri? = runCatching {
        val doc = PdfDocument()
        try {
            val pages = Pages(doc)
            pages.document(snapshots)
            snapshots.forEachIndexed { index, snapshot ->
                if (index > 0) pages.divider()
                pages.snapshot(snapshot, dateOf(snapshot.timeMillis))
            }
            pages.finish()

            val dir = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
            val now = System.currentTimeMillis()
            dir.listFiles()?.filter { now - it.lastModified() > KEEP_MILLIS }?.forEach { it.delete() }
            val file = File(dir, fileName(snapshots))
            file.outputStream().use { doc.writeTo(it) }
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        } finally {
            doc.close()
        }
    }.getOrNull()

    /**
     * Открыть PDF. Сначала - просмотрщиком (в нём же есть "Поделиться"), а
     * если на телефоне его нет - сразу диалог "Отправить".
     */
    fun open(context: Context, uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(view)
        } catch (missing: ActivityNotFoundException) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, null))
        }
    }

    private fun fileName(snapshots: List<DtcSnapshot>): String {
        val format = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
        val stamp = if (snapshots.size == 1) {
            format.format(Date(snapshots.first().timeMillis))
        } else {
            "all_" + format.format(Date())
        }
        return "obd_dtc_$stamp.pdf"
    }

    /** Раскладка по страницам: текст переносится сам, страница начинается, когда кончилась старая. */
    private class Pages(private val doc: PdfDocument) {

        private var page: PdfDocument.Page? = null
        private var number = 0
        private var y = MARGIN
        private val width = PAGE_WIDTH - 2 * MARGIN

        private val ink = Color.rgb(0x16, 0x18, 0x1D)
        private val muted = Color.rgb(0x5D, 0x64, 0x70)
        private val red = Color.rgb(0xB0, 0x2A, 0x2A)

        private val title = paint(17f, Typeface.DEFAULT_BOLD, ink)
        private val heading = paint(12.5f, Typeface.DEFAULT_BOLD, ink)
        private val section = paint(10.5f, Typeface.DEFAULT_BOLD, ink)
        private val body = paint(9.6f, Typeface.DEFAULT, ink)
        private val small = paint(8.4f, Typeface.DEFAULT, muted)
        private val line = Paint().apply { color = Color.rgb(0xD9, 0xDD, 0xE3); strokeWidth = 0.8f }

        private fun paint(size: Float, face: Typeface, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = face
            this.color = color
        }

        fun document(snapshots: List<DtcSnapshot>) {
            text(ObdText.pick("Архив ошибок", "Fault code archive"), title, after = 2f)
            val now = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
            val vehicles = snapshots.map { it.vehicle }.distinct().joinToString(", ")
            text(
                listOf(
                    vehicles,
                    ObdText.pick("записей: ", "records: ") + snapshots.size,
                    ObdText.pick("выгружено ", "exported ") + now,
                ).filter { it.isNotBlank() }.joinToString(" · "),
                small,
                after = 12f,
            )
        }

        fun snapshot(snapshot: DtcSnapshot, date: String) {
            val head = if (snapshot.beforeClear) {
                date + ObdText.pick(" · перед сбросом ошибок", " · before clearing faults")
            } else {
                date
            }
            text(head, heading, after = 2f, keep = 40f)
            val meta = buildList {
                add(snapshot.vehicle)
                snapshot.vin?.let { add("VIN $it") }
                add(if (snapshot.milOn) ObdText.pick("Check Engine горел", "Check Engine on") else ObdText.pick("Check Engine не горел", "Check Engine off"))
                add(ObdText.pick("кодов: ", "codes: ") + snapshot.total)
            }
            text(meta.joinToString(" · "), small, after = 6f)

            if (snapshot.isEmpty) {
                text(ObdText.pick("Ошибок не было", "No faults were found"), body, after = 4f)
                return
            }

            codes(snapshot, ObdText.pick("Сохранённые (режим 03)", "Stored (mode 03)"), snapshot.stored)
            codes(snapshot, ObdText.pick("Ожидающие подтверждения (режим 07)", "Pending (mode 07)"), snapshot.pending)
            codes(snapshot, ObdText.pick("Постоянные (режим 0A)", "Permanent (mode 0A)"), snapshot.permanent)

            for ((address, modules) in snapshot.modules.groupBy { it.address }) {
                text(DtcArchive.moduleTitle(address) + " · " + modules.size, section, before = 6f, after = 3f, keep = 30f)
                modules.forEachIndexed { index, module ->
                    val color = when (UdsStatus.isReal(module.status)) {
                        true -> red
                        false -> muted
                        null -> ink
                    }
                    row(index + 1, module.code, snapshot.describe(module.code), color, module.statusText)
                }
            }

            val moduleCodes = snapshot.modules
            if (moduleCodes.any { it.status == null }) {
                text(
                    ObdText.pick(
                        "Статус кодов блоков в этой записи не сохранялся (запись сделана прошлой версией приложения): по ней не сказать, какие ошибки были активны, а какие - только записи в памяти.",
                        "Module code status was not saved in this record (made by an older app version), so it cannot tell active faults from memory records."
                    ),
                    small,
                    before = 4f,
                )
            } else if (moduleCodes.any { UdsStatus.isReal(it.status) == false }) {
                text(
                    ObdText.pick(
                        "Серым - записи, которые блок отдаёт, но неисправностью сейчас не считает (тест не проходил после сброса, только запись в памяти). Красным - ошибка есть сейчас, подтверждена или ждёт подтверждения.",
                        "Grey: records the module reports but does not treat as a current fault. Red: failing now, confirmed or pending."
                    ),
                    small,
                    before = 4f,
                )
            }
        }

        private fun codes(snapshot: DtcSnapshot, header: String, codes: List<String>) {
            if (codes.isEmpty()) return
            text("$header · ${codes.size}", section, before = 6f, after = 3f, keep = 30f)
            codes.forEachIndexed { index, code -> row(index + 1, code, snapshot.describe(code), ink, null) }
        }

        /** Строка кода: номер, код жирным, расшифровка; статус - мелко под ней. */
        private fun row(index: Int, code: String, description: String, color: Int, status: String?) {
            val rowText = SpannableStringBuilder()
            rowText.append("$index. ")
            rowText.setSpan(ForegroundColorSpan(muted), 0, rowText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = rowText.length
            rowText.append(code)
            rowText.setSpan(StyleSpan(Typeface.BOLD), start, rowText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rowText.setSpan(ForegroundColorSpan(color), start, rowText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Семейство строкой, а не Typeface: конструктор с Typeface есть только с Android 9.
            rowText.setSpan(TypefaceSpan("monospace"), start, rowText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rowText.append("  ").append(description)
            text(rowText, body, after = if (status == null) 4f else 1f, keep = if (status == null) 0f else 12f)
            if (status != null) {
                text(status, paint(8.2f, Typeface.DEFAULT, if (color == red) red else muted), indent = 16f, after = 4f)
            }
        }

        fun divider() {
            ensure(24f)
            y += 8f
            canvas().drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, line)
            y += 12f
        }

        /**
         * Абзац с переносом строк. [keep] - сколько места должно остаться под
         * абзацем на этой же странице: заголовок не остаётся внизу страницы в
         * одиночестве, а код - без своего статуса.
         */
        private fun text(
            value: CharSequence,
            paint: TextPaint,
            indent: Float = 0f,
            before: Float = 0f,
            after: Float = 3f,
            keep: Float = 0f,
        ) {
            val layout = StaticLayout.Builder
                .obtain(value, 0, value.length, paint, (width - indent).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .build()
            val height = layout.height.toFloat()
            y += before
            ensure(height + keep)
            val canvas = canvas()
            canvas.save()
            canvas.translate(MARGIN + indent, y)
            layout.draw(canvas)
            canvas.restore()
            y += height + after
        }

        private fun ensure(height: Float) {
            if (page == null || y + height > PAGE_HEIGHT - MARGIN - FOOTER) newPage()
        }

        private fun canvas() = (page ?: newPage()).canvas

        private fun newPage(): PdfDocument.Page {
            page?.let { closePage(it) }
            number++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create()
            val next = doc.startPage(info)
            page = next
            y = MARGIN
            return next
        }

        private fun closePage(current: PdfDocument.Page) {
            current.canvas.drawText(
                ObdText.pick("OBD Сканер · страница ", "OBD Scanner · page ") + number,
                MARGIN,
                PAGE_HEIGHT - MARGIN / 2,
                small,
            )
            doc.finishPage(current)
        }

        fun finish() {
            page?.let { closePage(it) }
            page = null
        }
    }
}
