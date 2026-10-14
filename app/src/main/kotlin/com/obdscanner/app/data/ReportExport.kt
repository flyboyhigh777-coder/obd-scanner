package com.obdscanner.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Выгрузка отчёта полной проверки текстовым файлом.
 *
 * Текст, а не PDF и не HTML, сознательно: отчёт чаще всего пересылают в
 * мессенджере и читают с телефона, а текст открывается всем и везде, ничего не
 * ломает при пересылке и остаётся читаемым, даже если его вставят прямо в
 * сообщение.
 *
 * Правила записи те же, что у выгрузки поездки: с Android 10 файл кладётся в
 * общие "Загрузки" без разрешений, на более старых - в приватный кеш, откуда
 * его можно отдать через "Поделиться". Просить доступ ко всей памяти телефона
 * ради одного текстового файла несоразмерно.
 */
object ReportExport {

    private const val DIRECTORY = "ObdScanner"
    private const val MIME = "text/plain"

    fun save(context: Context, text: String): ExportResult {
        val name = "obd_report_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloads(context, name, text)?.let { return ExportResult(it, inDownloads = true) }
        }
        return shareableFile(context, name, text)?.let { ExportResult(it, inDownloads = false) }
            ?: ExportResult.FAILED
    }

    private fun saveToDownloads(context: Context, name: String, text: String): Uri? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIRECTORY)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return@runCatching null
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        uri
    }.getOrNull()

    private fun shareableFile(context: Context, name: String, text: String): Uri? = runCatching {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(text)
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull()

    /** Интент "Поделиться" для уже сохранённого отчёта. */
    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
