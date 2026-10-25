package com.obdscanner.core

import com.obdscanner.core.obd.Pids
import com.obdscanner.core.trip.TripLog
import com.obdscanner.core.trip.TripRecorder
import com.obdscanner.core.trip.TripSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Журнал поездки: запись на диск и чтение обратно.
 *
 * Проверяется то, ради чего журнал и нужен: записанное в дороге должно
 * прочитаться потом. Поэтому тесты идут «туда и обратно» - пишем замеры,
 * разбираем файл и сверяем значения, а не просто смотрим на строку.
 */
class TripLogTest {

    private val start = 1_700_000_000_000L
    private val pids = listOf(Pids.RPM.id, Pids.COOLANT_TEMP.id, Pids.SPEED.id)

    private fun sample(secondsFromStart: Long, vararg values: Pair<Int, Double>) =
        TripSample(start + secondsFromStart * 1000L, values.toMap())

    private fun write(samples: List<TripSample>): String =
        TripLog.head(start, pids) + samples.joinToString("") { TripLog.line(start, it, pids) }

    @Test
    /** записали поездку - прочитали её же. */
    fun roundTrip() {
        val samples = listOf(
            sample(0, Pids.RPM.id to 820.0, Pids.COOLANT_TEMP.id to 23.0, Pids.SPEED.id to 0.0),
            sample(60, Pids.RPM.id to 1616.0, Pids.COOLANT_TEMP.id to 68.0, Pids.SPEED.id to 51.0),
            sample(120, Pids.RPM.id to 1636.0, Pids.COOLANT_TEMP.id to 90.0, Pids.SPEED.id to 65.0)
        )
        val file = TripLog.parse(write(samples))
        assertNotNull(file)
        file!!
        assertEquals(start, file.header.startMillis)
        assertEquals(pids, file.header.pids)
        assertEquals(3, file.samples.size)
        assertEquals(68.0, file.samples[1].values[Pids.COOLANT_TEMP.id]!!, 0.001)
        assertEquals(65.0, file.samples[2].values[Pids.SPEED.id]!!, 0.001)
        // Время восстановилось: вторая точка - через минуту после начала.
        assertEquals(start + 60_000L, file.samples[1].timeMillis)
    }

    @Test
    /**
     * Параметр, который замолчал, оставляет пустую клетку - а не ноль.
     *
     * Разница существенная: ноль вольт на бортовой сети и «датчик не ответил» -
     * это разные вещи, и нарисовать первое вместо второго значит соврать на
     * графике.
     */
    fun missingValuesStayMissing() {
        val samples = listOf(
            sample(0, Pids.RPM.id to 800.0, Pids.COOLANT_TEMP.id to 20.0),
            sample(10, Pids.RPM.id to 900.0)
        )
        val file = TripLog.parse(write(samples))!!
        assertNull(file.samples[1].values[Pids.COOLANT_TEMP.id])
        assertEquals(900.0, file.samples[1].values[Pids.RPM.id]!!, 0.001)
    }

    @Test
    /** файл открывается таблицей: служебная строка одна, дальше обычный CSV. */
    fun fileIsReadableAsCsv() {
        val text = write(listOf(sample(0, Pids.RPM.id to 800.0)))
        val lines = text.lines()
        assertTrue("служебная строка помечена", lines[0].startsWith("#"))
        assertTrue("в ней версия и начало", lines[0].contains("v=1") && lines[0].contains("start=$start"))
        assertTrue("вторая строка - названия столбцов", lines[1].startsWith("Секунды;Время;"))
        // Секунды, время суток и три параметра.
        assertEquals(5, lines[1].split(';').size)
        assertTrue("названия - на языке приложения", lines[1].contains("Обороты двигателя"))
        assertTrue("первая запись - с нуля секунд", lines[2].startsWith("0.0;"))
        // Время суток стоит рядом с секундами: «1249.97» не говорит ничего.
        assertTrue("во второй клетке - часы", lines[2].split(';')[1].matches(Regex("\\d{2}:\\d{2}:\\d{2}")))
    }

    @Test
    /**
     * Файл объясняет сам себя.
     *
     * Через полгода человек открывает его и должен сразу увидеть, что это за
     * поездка, а не считать её длину по последней строке.
     */
    fun fileEndsWithItsOwnSummary() {
        val stats = TripRecorder().apply {
            add(sample(0, Pids.SPEED.id to 0.0, Pids.RPM.id to 800.0, Pids.COOLANT_TEMP.id to 20.0))
            add(sample(60, Pids.SPEED.id to 60.0, Pids.RPM.id to 2000.0, Pids.COOLANT_TEMP.id to 88.0))
        }.stats()
        val tail = TripLog.tail(stats)
        assertTrue(tail.startsWith("#"))
        assertTrue("виден пробег", tail.contains("пробег_км="))
        assertTrue("видна максимальная температура", tail.contains("макс_температура_ОЖ=88"))
        assertTrue("видно, сколько замеров", tail.contains("замеров=2"))
    }

    @Test
    /** чужой или испорченный файл не роняет приложение. */
    fun brokenFileIsJustNull() {
        assertNull(TripLog.parse(""))
        assertNull(TripLog.parse("просто текст\nи ещё строка"))
        assertNull(TripLog.parse("#obdlog;v=1;pids=12"))
        // Обрезанный на середине строки файл читается до места обрыва: так
        // бывает, когда телефон выключился в дороге, и терять из-за этого всю
        // поездку незачем.
        val cut = write(listOf(sample(0, Pids.RPM.id to 800.0), sample(5, Pids.RPM.id to 900.0)))
            .dropLast(4)
        val file = TripLog.parse(cut)
        assertNotNull(file)
        assertTrue("что успели записать - то и прочитали", file!!.samples.isNotEmpty())
    }

    @Test
    /** по параметру виден его ход: точки, минимум, максимум, среднее. */
    fun seriesTellsWhatHappened() {
        val samples = listOf(
            sample(0, Pids.COOLANT_TEMP.id to 20.0),
            sample(60, Pids.COOLANT_TEMP.id to 60.0),
            sample(120, Pids.COOLANT_TEMP.id to 90.0),
            sample(180, Pids.RPM.id to 800.0)
        )
        val file = TripLog.parse(write(samples))!!
        val series = TripLog.series(file, Pids.COOLANT_TEMP.id)
        assertEquals(3, series.points.size)
        assertEquals(20.0, series.min, 0.001)
        assertEquals(90.0, series.max, 0.001)
        assertEquals(56.667, series.average, 0.01)
        // Точки расставлены по времени от начала поездки.
        assertEquals(0.0, series.points[0].seconds, 0.01)
        assertEquals(120.0, series.points[2].seconds, 0.01)

        // Параметра, которого в поездке не было, просто нет.
        assertTrue(TripLog.series(file, Pids.FUEL_LEVEL.id).isEmpty)
    }

    @Test
    /** на экране предлагаются только те параметры, которые реально записаны. */
    fun onlyRecordedParametersAreOffered() {
        val file = TripLog.parse(
            write(listOf(sample(0, Pids.RPM.id to 800.0, Pids.SPEED.id to 0.0)))
        )!!
        val recorded = TripLog.recorded(file).map { it.id }
        assertTrue(Pids.RPM.id in recorded)
        assertTrue(Pids.SPEED.id in recorded)
        // Столбец заявлен в заголовке, но ни одного значения не пришло.
        assertTrue(Pids.COOLANT_TEMP.id !in recorded)
    }

    @Test
    /**
     * Прореживание: в журнал идёт один замер за промежуток.
     *
     * Опрос идёт по несколько раз в секунду; без прореживания получились бы
     * мегабайты в час и таблица, в которой ничего не разглядеть.
     */
    fun thinningKeepsOneSamplePerInterval() {
        val interval = 5_000L
        // Первый замер пишется сразу: короткая поездка иначе не записалась бы.
        assertTrue(TripLog.due(null, start, interval))
        assertTrue("прошло ровно столько, сколько ждали", TripLog.due(start, start + 5_000, interval))
        assertTrue(TripLog.due(start, start + 7_000, interval))
        assertEquals(false, TripLog.due(start, start + 4_999, interval))
        // Нулевой промежуток - пишем всё подряд (режим отладки).
        assertTrue(TripLog.due(start, start + 1, 0L))
    }
}
