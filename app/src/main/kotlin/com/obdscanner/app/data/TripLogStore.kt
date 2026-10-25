package com.obdscanner.app.data

import android.content.Context
import com.obdscanner.core.trip.TripLog
import com.obdscanner.core.trip.TripRecorder
import com.obdscanner.core.trip.TripSample
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Одна поездка в списке: файл и то, что видно про него без полного разбора. */
data class TripLogEntry(
    val file: File,
    val startMillis: Long,
    val sizeBytes: Long,
    /** Итог из последней строки файла. Нет - значит запись не закончена. */
    val summary: TripLog.Summary? = null
) {
    val name: String get() = file.name
    val finished: Boolean get() = summary != null
}

/**
 * Где лежат журналы поездок.
 *
 * Внутренняя память приложения, а не общая папка: файл пишется весь путь, и
 * просить ради этого доступ ко всей памяти телефона несоразмерно. Отдать
 * файл наружу можно и так - кнопкой «поделиться», и там уже человек сам
 * решает, куда его положить.
 *
 * Запись идёт строка за строкой с немедленным сбросом на диск. Это дороже,
 * чем копить в памяти, но journal на то и journal: телефон в машине живёт
 * своей жизнью - садится, уходит в сон, его закрывают, - и записанное до
 * этого момента должно остаться записанным.
 */
class TripLogStore(context: Context) {

    private val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /**
     * Начать новую поездку.
     *
     * [pids] - столбцы на весь файл. Набор фиксируется здесь и не меняется:
     * иначе столбцы разъехались бы по ходу файла.
     */
    fun start(startMillis: Long, pids: List<Int>, dieselFuel: Boolean): TripLogWriter? = try {
        val file = File(directory, fileName(startMillis))
        file.appendText(TripLog.head(startMillis, pids))
        TripLogWriter(file, startMillis, pids, dieselFuel)
    } catch (e: IOException) {
        null
    }

    /**
     * Поездки, самая свежая сверху.
     *
     * Файл читается одним проходом и только ради двух строк: первой (когда
     * поездка началась и что в ней записано) и последней (итог). Разбирать
     * весь журнал ради списка незачем - его открывают, когда выбрали.
     */
    fun list(): List<TripLogEntry> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(SUFFIX) }
        .mapNotNull { file ->
            var header: TripLog.Header? = null
            var last: String? = null
            runCatching {
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (index == 0) header = TripLog.parseHead(line)
                        if (line.isNotBlank()) last = line
                    }
                }
            }
            val start = header?.startMillis ?: return@mapNotNull null
            TripLogEntry(
                file = file,
                startMillis = start,
                sizeBytes = file.length(),
                summary = last?.let(TripLog::parseTail)
            )
        }
        .sortedByDescending { it.startMillis }

    /** Прочитать поездку целиком. */
    fun read(entry: TripLogEntry): TripLog.File? =
        runCatching { TripLog.parse(entry.file.readText()) }.getOrNull()

    fun delete(entry: TripLogEntry): Boolean = runCatching { entry.file.delete() }.getOrDefault(false)

    fun deleteAll() {
        directory.listFiles().orEmpty().forEach { runCatching { it.delete() } }
    }

    /**
     * Оставить только последние поездки.
     *
     * Без этого журнал растёт вечно: час езды с записью раз в пять секунд -
     * это около семидесяти килобайт, то есть за год ежедневных поездок
     * наберётся немного, но «немного навсегда» - это всё равно утечка. Старое
     * удаляется молча: человек, который следит за местом на телефоне, не
     * ищет его в приложении для диагностики.
     */
    fun trim(keep: Int = KEEP_TRIPS) {
        list().drop(keep.coerceAtLeast(1)).forEach { delete(it) }
    }

    private fun fileName(startMillis: Long): String =
        "trip-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(startMillis)) + SUFFIX

    private companion object {
        const val DIRECTORY = "triplog"
        const val SUFFIX = ".csv"

        /** Сколько поездок храним. Двадцать - это около месяца ежедневных поездок. */
        const val KEEP_TRIPS = 20
    }
}

/**
 * Открытый на запись журнал.
 *
 * Ошибки записи не роняют приложение и не прекращают поездку: диагностика
 * важнее журнала. Если писать не удалось, запись просто останавливается -
 * молча, но с пометкой [failed], чтобы экран мог об этом сказать.
 */
class TripLogWriter(
    val file: File,
    val startMillis: Long,
    private val pids: List<Int>,
    private val dieselFuel: Boolean
) {
    /**
     * Те же замеры в памяти - только ради итога в конце файла.
     *
     * Их немного: при записи раз в пять секунд час езды - это 720 точек.
     * Считать итог, перечитывая файл с диска, было бы дороже и сложнее.
     */
    private val recorder = TripRecorder()

    var samples: Int = 0
        private set

    var failed: Boolean = false
        private set

    var lastWrittenMillis: Long? = null
        private set

    /** Записать замер, если подошёл срок. Возвращает true, если записали. */
    fun append(sample: TripSample, intervalMillis: Long): Boolean {
        if (failed) return false
        if (!TripLog.due(lastWrittenMillis, sample.timeMillis, intervalMillis)) return false
        return try {
            file.appendText(TripLog.line(startMillis, sample, pids))
            recorder.add(sample)
            lastWrittenMillis = sample.timeMillis
            samples++
            true
        } catch (e: IOException) {
            failed = true
            false
        }
    }

    /**
     * Закончить поездку.
     *
     * Слишком короткая запись удаляется: подключился, посмотрел напряжение и
     * вышел - это не поездка, а мусор в списке. Возвращает true, если файл
     * остался.
     */
    fun close(minSamples: Int = MIN_SAMPLES): Boolean {
        if (samples >= minSamples) {
            // Итог в конце файла: открыв его через полгода, человек сразу
            // видит, что это была за поездка.
            runCatching { file.appendText(TripLog.tail(recorder.stats(dieselFuel))) }
            return true
        }
        runCatching { file.delete() }
        return false
    }

    private companion object {
        /** Меньше трёх точек не нарисуют никакого хода параметра. */
        const val MIN_SAMPLES = 3
    }
}
