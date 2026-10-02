package com.obdscanner.app.data

import android.content.Context
import com.obdscanner.core.dtc.ManufacturerDtc

/**
 * Таблица кодов из документации на машину - из ресурсов приложения.
 *
 * Зачем файлом, а не в коде: встроенная сводка по контроллеру собрана по
 * открытым описаниям и заведомо неполная, а руководство по ремонту есть далеко
 * не у всех и у каждого своё. Дописать строку в текстовый файл может любой, и
 * для этого не нужно ни знать Kotlin, ни пересобирать приложение заново - только
 * положить файл в сборку.
 *
 * Сам разбор живёт в :core и покрыт тестами; здесь только чтение файла.
 */
object ManufacturerDtcAsset {

    private const val ASSET = "dtc_me17_9_7.tsv"

    @Volatile
    private var loadedCount: Int = -1

    /** Сколько кодов прочитано из файла; -1 - файл ещё не читали. */
    val size: Int get() = loadedCount.coerceAtLeast(0)

    val loaded: Boolean get() = loadedCount >= 0

    /** Читает файл и подключает коды к справочнику. Вызывать из фонового потока. */
    fun load(context: Context) {
        if (loadedCount >= 0) return
        val codes = runCatching {
            context.assets.open(ASSET).bufferedReader().use { reader ->
                ManufacturerDtc.parseDocumentationTable(reader.readText())
            }
        }.getOrNull() ?: emptyMap()
        ManufacturerDtc.fromDocumentation = codes
        loadedCount = codes.size
    }
}
