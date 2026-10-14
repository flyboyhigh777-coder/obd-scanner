package com.obdscanner.app.data

import android.content.Context
import com.obdscanner.core.dtc.DtcArchive
import com.obdscanner.core.dtc.DtcSnapshot
import java.io.File

/**
 * Архив прочитанных ошибок на диске.
 *
 * Лежит обычным текстом в личной папке приложения: файл можно вытащить,
 * открыть блокнотом и прочитать без всякого приложения. Разбор формата - в
 * :core и покрыт тестами, здесь только файл.
 *
 * Запись после каждого чтения ошибок - это несколько килобайт раз в несколько
 * минут, так что отдельный поток ради неё не нужен; но и терять архив из-за
 * того, что в файл не удалось записать, тоже незачем - отсюда runCatching.
 */
object DtcArchiveStore {

    private const val FILE = "dtc_archive.tsv"

    @Volatile
    private var cache: List<DtcSnapshot>? = null

    fun load(context: Context): List<DtcSnapshot> {
        cache?.let { return it }
        val file = File(context.filesDir, FILE)
        val list = if (file.exists()) {
            runCatching { DtcArchive.decode(file.readText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        cache = list
        return list
    }

    /** Добавляет чтение и возвращает архив целиком - уже обрезанный по пределу. */
    fun add(context: Context, snapshot: DtcSnapshot): List<DtcSnapshot> {
        val merged = DtcArchive.merge(load(context), snapshot)
        cache = merged
        runCatching { File(context.filesDir, FILE).writeText(DtcArchive.encode(merged)) }
        return merged
    }

    fun clear(context: Context): List<DtcSnapshot> {
        cache = emptyList()
        runCatching { File(context.filesDir, FILE).delete() }
        return emptyList()
    }
}
