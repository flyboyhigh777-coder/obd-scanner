package com.obdscanner.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.obdscanner.core.diag.ReportBlock
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Выгрузка отчёта о диагностике: PDF и текст.
 *
 * PDF - основной формат. Текст на телефоне открывать нечем: просмотрщика .txt
 * в Android нет, файл из мессенджера человек открывает браузером, а тот для
 * локального файла угадывает кодировку - и для кириллицы угадывает неверно.
 *
 * Текстовый вариант остаётся для тех, кому отчёт нужно вставить в сообщение
 * или в почту целиком. В нём первым делом пишется метка UTF-8 (BOM): без неё
 * ровно та же беда, файл честно записан в UTF-8, а открывается кракозябрами,
 * потому что программа-просмотрщик решила, что это windows-1251.
 *
 * Правила записи одинаковые для обоих: с Android 10 файл кладётся в общие
 * "Загрузки" без разрешений, на более старых - в приватный кеш, откуда его
 * можно отдать через "Поделиться". Просить доступ ко всей памяти телефона ради
 * одного отчёта несоразмерно.
 */
object ReportExport {

    private const val DIRECTORY = "ObdScanner"
    const val MIME_PDF = "application/pdf"
    const val MIME_TEXT = "text/plain"

    /** Метка UTF-8: три байта, которые избавляют от кракозябр у получателя. */
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun savePdf(context: Context, blocks: List<ReportBlock>, footer: String): ExportResult {
        val bytes = ByteArrayOutputStream().use { stream ->
            runCatching { ReportPdf.write(blocks, footer, stream) }.getOrNull() ?: return ExportResult.FAILED
            stream.toByteArray()
        }
        return save(context, name("pdf"), MIME_PDF, bytes)
    }

    fun saveText(context: Context, text: String): ExportResult =
        save(context, name("txt"), MIME_TEXT, UTF8_BOM + text.toByteArray(Charsets.UTF_8))

    private fun name(extension: String): String =
        "obd_report_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + extension

    private fun save(context: Context, name: String, mime: String, bytes: ByteArray): ExportResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloads(context, name, mime, bytes)?.let { return ExportResult(it, inDownloads = true) }
        }
        return shareableFile(context, name, bytes)?.let { ExportResult(it, inDownloads = false) }
            ?: ExportResult.FAILED
    }

    private fun saveToDownloads(context: Context, name: String, mime: String, bytes: ByteArray): Uri? =
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIRECTORY)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching null
            // IS_PENDING=0 делает файл видимым остальным приложениям только
            // после того, как он полностью записан.
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        }.getOrNull()

    private fun shareableFile(context: Context, name: String, bytes: ByteArray): Uri? = runCatching {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull()

    /** Интент "Поделиться" для уже сохранённого отчёта. */
    fun shareIntent(uri: Uri, mime: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
