package com.obdscanner.app.data

import android.content.Context
import com.obdscanner.core.dtc.ManufacturerDtc
import java.io.File

/**
 * Расшифровки кодов из документации на машину - из двух источников сразу.
 *
 *  * `assets/dtc_me17_9_7.tsv` - таблица, положенная в сборку. Её правит тот,
 *    кто собирает приложение.
 *  * `dtc_user.tsv` в личной папке приложения - то, что человек ввёл сам, в
 *    приложении: своей строкой по коду или импортом файла с телефона.
 *
 * Зачем второй источник. Машиночитаемой таблицы кодов P1xxx для контроллеров
 * ME17.9.7/ME17.9.71 в открытом виде нет - ни в открытых базах, ни на сайтах,
 * доступных сборочной среде (см. data/SOURCES.md). Поэтому приложение не должно
 * зависеть от того, добудет ли её автор: расшифровку, найденную в руководстве
 * по ремонту или у диагноста, человек вписывает сам один раз, и она остаётся.
 *
 * Своё важнее встроенного: если код есть в обоих файлах, показывается введённое
 * человеком - он смотрит в руководство на свою машину, а сборка несёт общую
 * таблицу.
 *
 * Сам разбор таблицы живёт в :core и покрыт тестами; здесь только файлы.
 */
object DocumentationDtc {

    private const val ASSET = "dtc_me17_9_7.tsv"
    private const val USER_FILE = "dtc_user.tsv"

    @Volatile
    private var fromAsset: Map<String, String> = emptyMap()

    @Volatile
    private var fromUser: Map<String, String> = emptyMap()

    @Volatile
    private var ready = false

    /** Сколько кодов пришло из сборки. */
    val assetSize: Int get() = fromAsset.size

    /** Сколько кодов человек добавил сам. */
    val userSize: Int get() = fromUser.size

    val size: Int get() = fromAsset.size + fromUser.keys.count { it !in fromAsset }

    val loaded: Boolean get() = ready

    /** Читает оба файла и подключает коды к справочнику. Вызывать из фонового потока. */
    fun load(context: Context) {
        if (ready) return
        fromAsset = runCatching {
            context.assets.open(ASSET).bufferedReader().use { reader ->
                ManufacturerDtc.parseDocumentationTable(reader.readText())
            }
        }.getOrDefault(emptyMap())
        fromUser = runCatching {
            val file = userFile(context)
            if (file.exists()) ManufacturerDtc.parseDocumentationTable(file.readText()) else emptyMap()
        }.getOrDefault(emptyMap())
        ready = true
        publish()
    }

    /** Расшифровка, введённая человеком для этого кода, если она есть. */
    fun userText(code: String): String? = fromUser[code.uppercase()]

    /** Своя расшифровка одного кода. Пустой текст удаляет её. */
    fun put(context: Context, code: String, text: String) {
        load(context)
        val key = code.uppercase().trim()
        val value = text.trim()
        if (key.isEmpty()) return
        fromUser = fromUser.toMutableMap().apply {
            if (value.isEmpty()) remove(key) else put(key, value)
        }
        save(context)
    }

    /**
     * Импорт таблицы из файла, выбранного на телефоне. Возвращает, сколько
     * кодов удалось разобрать: ноль означает, что файл не той формы, и об этом
     * нужно сказать, а не молча показать "готово".
     */
    fun import(context: Context, text: String): Int {
        load(context)
        val parsed = ManufacturerDtc.parseDocumentationTable(text)
        if (parsed.isEmpty()) return 0
        fromUser = fromUser + parsed
        save(context)
        return parsed.size
    }

    /** Убирает всё, что было введено вручную; таблица из сборки остаётся. */
    fun clearUser(context: Context) {
        load(context)
        fromUser = emptyMap()
        runCatching { userFile(context).delete() }
        publish()
    }

    private fun userFile(context: Context) = File(context.filesDir, USER_FILE)

    private fun save(context: Context) {
        runCatching {
            val text = buildString {
                append("# Расшифровки кодов, введённые в приложении.\n")
                append("# Формат: код<TAB>описание. Можно править и переносить на другой телефон.\n")
                fromUser.toSortedMap().forEach { (code, description) ->
                    append(code).append('\t').append(description).append('\n')
                }
            }
            userFile(context).writeText(text)
        }
        publish()
    }

    /** Карта в :core одна, поэтому оба источника сводятся здесь, а не там. */
    private fun publish() {
        ManufacturerDtc.fromDocumentation = fromAsset + fromUser
    }
}
