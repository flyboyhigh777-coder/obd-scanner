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
 * Куда лёг файл: в общую папку загрузок (её видно из файлового менеджера и с
 * компьютера) или только во внутренний кеш, откуда его можно лишь отправить.
 */
data class ExportResult(val uri: Uri?, val inDownloads: Boolean) {
    val success: Boolean get() = uri != null

    companion object {
        val FAILED = ExportResult(null, false)
    }
}

/**
 * Выгрузка записи поездки в CSV.
 *
 * С Android 10 (API 29) запись в общие загрузки через MediaStore разрешений не
 * требует. На Android 9 (наш minSdk - 26) потребовалось бы
 * WRITE_EXTERNAL_STORAGE, а просить доступ ко всей памяти телефона ради одного
 * CSV - несоразмерно: там файл кладётся в приватный кеш и отдаётся через
 * "Поделиться" (FileProvider), что не требует вообще ничего.
 */
object TripExport {

    private const val DIRECTORY = "ObdScanner"
    private const val MIME = "text/csv"

    fun save(context: Context, csv: String): ExportResult {
        val name = "obd_trip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloads(context, name, csv)?.let { return ExportResult(it, inDownloads = true) }
        }
        return shareableFile(context, name, csv)?.let { ExportResult(it, inDownloads = false) }
            ?: ExportResult.FAILED
    }

    private fun saveToDownloads(context: Context, name: String, csv: String): Uri? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIRECTORY)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } ?: return@runCatching null
        // IS_PENDING=0 делает файл видимым для остальных приложений только после
        // того, как он полностью записан - иначе файловый менеджер может успеть
        // показать обрезанный CSV.
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        uri
    }.getOrNull()

    private fun shareableFile(context: Context, name: String, csv: String): Uri? = runCatching {
        val dir = File(context.cacheDir, "trips").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(csv)
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull()

    /** Интент "Поделиться" для уже сохранённого файла. */
    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
