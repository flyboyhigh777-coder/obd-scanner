package com.obdscanner.core.trip

import com.obdscanner.core.obd.ObdPid
import com.obdscanner.core.obd.Pids
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Журнал поездки - «чёрный ящик».
 *
 * Приборка показывает то, что происходит прямо сейчас, и всё это пропадает
 * вместе со связью: заглушил двигатель - и смотреть больше нечего. А вопросы
 * возникают потом: какая была температура на подъёме, когда выросла долгая
 * коррекция, сколько держались обороты. Поэтому показания пишутся на диск по
 * ходу поездки и остаются там после неё.
 *
 * Формат - обычный CSV с одной служебной строкой сверху. Это сделано
 * намеренно: файл открывается в любой таблице без всякого приложения, и то,
 * что человек увидит у себя на компьютере, - те же числа, которые приложение
 * показывает ему на экране. Служебная строка начинается с «#», и таблицы её
 * игнорируют.
 *
 * Файл дописывается строка за строкой, а не сохраняется целиком в конце: с
 * машиной бывает всё - связь рвётся, телефон уходит в сон, приложение
 * закрывают. Записанное до этого момента должно остаться записанным.
 */
object TripLog {

    /** Версия формата: если когда-нибудь поменяется, старые файлы узнаются по ней. */
    const val VERSION = 1

    /** Служебная строка в начале файла. */
    private const val MARK = "#obdlog"

    private const val SEPARATOR = ';'

    /** Чем начинается строка с итогом. */
    private const val TAIL = "$MARK-итог"

    /**
     * Заголовок: когда началась поездка и какие параметры в ней записаны.
     *
     * Набор параметров фиксируется в начале и больше не меняется. Иначе
     * столбцы разъезжались бы по ходу файла, и он перестал бы быть таблицей -
     * ради чего всё и затевалось.
     */
    data class Header(val startMillis: Long, val pids: List<Int>, val version: Int = VERSION)

    /** Разобранный файл журнала. */
    data class File(val header: Header, val samples: List<TripSample>)

    /** Одна точка на графике параметра. */
    data class Point(val seconds: Double, val value: Double)

    /** Как менялся один параметр за поездку. */
    data class Series(
        val pid: Int,
        val points: List<Point>,
        val min: Double,
        val max: Double,
        val average: Double
    ) {
        val isEmpty: Boolean get() = points.isEmpty()
    }

    // --- запись ------------------------------------------------------------

    /** Служебная строка плюс строка с названиями столбцов. */
    fun head(startMillis: Long, pids: List<Int>): String {
        val mark = buildString {
            append(MARK)
            append(SEPARATOR).append("v=").append(VERSION)
            append(SEPARATOR).append("start=").append(startMillis)
            append(SEPARATOR).append("pids=").append(pids.joinToString(","))
        }
        // Названия - те же, что человек видит на экране, и на его языке.
        // Файл, в котором «Coolant temperature», требует догадаться, что это
        // температура охлаждающей жидкости, - а он открывается ровно затем,
        // чтобы не догадываться.
        val titles = buildString {
            append(COLUMN_SECONDS).append(SEPARATOR).append(COLUMN_CLOCK)
            pids.forEach { id ->
                val pid = Pids.byId[id]
                append(SEPARATOR)
                append(pid?.let { "${it.title.replace(SEPARATOR, ',')}, ${it.unit.label}" } ?: "PID $id")
            }
        }
        return "$mark\n$titles\n"
    }

    /**
     * Строка-итог в конце файла.
     *
     * Файл должен объяснять сам себя: открыв его через полгода, человек
     * должен сразу увидеть, что это за поездка, а не считать её длину по
     * последней строке. Строка начинается с «#», и таблицы её игнорируют.
     */
    fun tail(stats: TripStats): String = buildString {
        append(TAIL)
        append(SEPARATOR).append("замеров=").append(stats.sampleCount)
        append(SEPARATOR).append("длительность_мин=")
            .append("%.1f".format(Locale.ROOT, stats.durationMillis / 60000.0))
        append(SEPARATOR).append("пробег_км=").append("%.1f".format(Locale.ROOT, stats.distanceKm))
        append(SEPARATOR).append("макс_скорость=").append("%.0f".format(Locale.ROOT, stats.maxSpeedKmh))
        append(SEPARATOR).append("макс_обороты=").append("%.0f".format(Locale.ROOT, stats.maxRpm))
        stats.maxCoolantC?.let {
            append(SEPARATOR).append("макс_температура_ОЖ=").append("%.0f".format(Locale.ROOT, it))
        }
        stats.consumptionL100Km?.let {
            append(SEPARATOR).append("расход_л_100км=").append("%.1f".format(Locale.ROOT, it))
        }
        append('\n')
    }

    /**
     * Одна строка замера.
     *
     * Время - секунды от начала поездки, а не метка времени: так строки
     * короче, читаются глазом и не зависят от часового пояса, в котором файл
     * потом откроют.
     */
    fun line(startMillis: Long, sample: TripSample, pids: List<Int>): String = buildString {
        append(seconds(sample.timeMillis - startMillis))
        // Время суток рядом с секундами: «1249.97» не говорит ничего, а
        // «18:48:25» - это момент, который человек помнит.
        append(SEPARATOR).append(clock(sample.timeMillis))
        pids.forEach { id ->
            append(SEPARATOR)
            val value = sample.values[id]
            if (value != null) append(number(value, Pids.byId[id]))
        }
        append('\n')
    }

    // --- чтение ------------------------------------------------------------

    /** Разобрать файл целиком. Чужой или испорченный файл даёт null, а не исключение. */
    fun parse(text: String): File? {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext()) return null
        val header = parseHead(lines.next()) ?: return null
        if (lines.hasNext()) lines.next() // строка с названиями столбцов
        val samples = ArrayList<TripSample>()
        while (lines.hasNext()) {
            parseLine(header, lines.next())?.let(samples::add)
        }
        return File(header, samples)
    }

    /**
     * Итог поездки, прочитанный из последней строки файла.
     *
     * Нужен списку поездок: показать «21 минута, 18 километров» можно, не
     * разбирая весь файл. У поездки, оборвавшейся вместе с приложением,
     * итога нет - это нормально и значит ровно то, что запись не закончена.
     */
    data class Summary(
        val samples: Int? = null,
        val durationMinutes: Double? = null,
        val distanceKm: Double? = null,
        val maxSpeedKmh: Double? = null,
        val maxRpm: Double? = null,
        val maxCoolantC: Double? = null,
        val consumptionL100Km: Double? = null
    )

    fun parseTail(line: String): Summary? {
        if (!line.startsWith(TAIL)) return null
        val fields = HashMap<String, String>()
        line.split(SEPARATOR).drop(1).forEach { part ->
            val key = part.substringBefore('=')
            if (key != part) fields[key] = part.substringAfter('=')
        }
        fun number(key: String) = fields[key]?.replace(',', '.')?.toDoubleOrNull()
        return Summary(
            samples = fields["замеров"]?.toIntOrNull(),
            durationMinutes = number("длительность_мин"),
            distanceKm = number("пробег_км"),
            maxSpeedKmh = number("макс_скорость"),
            maxRpm = number("макс_обороты"),
            maxCoolantC = number("макс_температура_ОЖ"),
            consumptionL100Km = number("расход_л_100км")
        )
    }

    /** Разобрать только служебную строку - этого хватает для списка поездок. */
    fun parseHead(line: String): Header? {
        if (!line.startsWith(MARK)) return null
        var version = VERSION
        var start = 0L
        var pids = emptyList<Int>()
        line.split(SEPARATOR).drop(1).forEach { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            when (key) {
                "v" -> version = value.toIntOrNull() ?: return null
                "start" -> start = value.toLongOrNull() ?: return null
                "pids" -> pids = value.split(',').mapNotNull { it.trim().toIntOrNull() }
            }
        }
        if (start <= 0L) return null
        return Header(startMillis = start, pids = pids, version = version)
    }

    private fun parseLine(header: Header, line: String): TripSample? {
        if (line.isBlank() || line.startsWith(MARK)) return null
        val parts = line.split(SEPARATOR)
        val seconds = parts.firstOrNull()?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val values = HashMap<Int, Double>()
        header.pids.forEachIndexed { index, id ->
            // +2: первые две клетки - секунды и время суток.
            val cell = parts.getOrNull(index + 2)?.trim()?.replace(',', '.')
            if (!cell.isNullOrEmpty()) cell.toDoubleOrNull()?.let { values[id] = it }
        }
        return TripSample(
            timeMillis = header.startMillis + (seconds * 1000.0).toLong(),
            values = values
        )
    }

    // --- что из этого показывать -------------------------------------------

    /**
     * Как менялся параметр за поездку.
     *
     * Среднее считается по замерам, а не по времени: замеры идут через равные
     * промежутки, и разница между этими двумя способами меньше, чем точность
     * самих датчиков.
     */
    fun series(file: File, pid: Int): Series {
        val points = file.samples.mapNotNull { sample ->
            sample.values[pid]?.let {
                Point(seconds = (sample.timeMillis - file.header.startMillis) / 1000.0, value = it)
            }
        }
        if (points.isEmpty()) return Series(pid, emptyList(), 0.0, 0.0, 0.0)
        return Series(
            pid = pid,
            points = points,
            min = points.minOf { it.value },
            max = points.maxOf { it.value },
            average = points.sumOf { it.value } / points.size
        )
    }

    /** Какие параметры в этом журнале вообще есть - для выбора на экране. */
    fun recorded(file: File): List<ObdPid> =
        file.header.pids.mapNotNull { Pids.byId[it] }
            .filter { pid -> file.samples.any { pid.id in it.values } }

    /**
     * Пора ли записывать очередной замер.
     *
     * Опрос идёт по несколько раз в секунду, и писать каждый ответ - это
     * мегабайты в час и нечитаемая таблица. В журнал попадает один замер за
     * выбранный промежуток; первый - сразу, иначе короткая поездка не
     * записалась бы вовсе.
     */
    fun due(lastWrittenMillis: Long?, nowMillis: Long, intervalMillis: Long): Boolean {
        if (lastWrittenMillis == null) return true
        if (intervalMillis <= 0L) return true
        return nowMillis - lastWrittenMillis >= intervalMillis
    }

    private fun seconds(millis: Long): String = "%.1f".format(Locale.ROOT, millis / 1000.0)

    private fun clock(millis: Long): String = CLOCK.format(Instant.ofEpochMilli(millis))

    private val CLOCK: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private const val COLUMN_SECONDS = "Секунды"
    private const val COLUMN_CLOCK = "Время"

    private fun number(value: Double, pid: ObdPid?): String =
        "%.${pid?.decimals ?: 2}f".format(Locale.ROOT, value)
}
