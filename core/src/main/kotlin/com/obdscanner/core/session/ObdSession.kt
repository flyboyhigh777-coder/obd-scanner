package com.obdscanner.core.session

import com.obdscanner.core.dtc.Dtc
import com.obdscanner.core.dtc.DtcCodec
import com.obdscanner.core.dtc.DtcSource
import com.obdscanner.core.elm.Elm
import com.obdscanner.core.elm.ElmError
import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.elm.ObdPayload
import com.obdscanner.core.elm.ObdProtocol
import com.obdscanner.core.elm.ObdText
import com.obdscanner.core.obd.CustomPid
import com.obdscanner.core.obd.MonitorStatus
import com.obdscanner.core.obd.ObdPid
import com.obdscanner.core.obd.PidScan
import com.obdscanner.core.obd.PidSupport
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.obd.ScanHit
import com.obdscanner.core.obd.VinDecoder
import java.io.Closeable

/** Этапы подключения - приложение показывает их строкой на экране "Подключение". */
enum class ConnectStage(val ru: String, val en: String) {
    RESET("Сброс адаптера", "Resetting the adapter"),
    CONFIGURE("Настройка адаптера", "Configuring the adapter"),
    PROTOCOL("Определение протокола машины", "Detecting the vehicle protocol"),
    SCAN_PIDS("Опрос поддерживаемых параметров", "Scanning supported parameters"),
    VEHICLE("Чтение данных автомобиля", "Reading vehicle data"),
    DONE("Готово", "Done");

    val title: String get() = ObdText.pick(ru, en)
}

sealed interface ConnectOutcome {
    data class Connected(
        val adapterId: String,
        val protocol: ObdProtocol?,
        val supportedPids: Set<Int>
    ) : ConnectOutcome

    data class Failed(val stage: ConnectStage, val error: ElmError?, val detail: String = "") : ConnectOutcome {
        val message: String
            get() = error?.text ?: ObdText.pick(
                "Не удалось: ${stage.title.lowercase()}",
                "Failed: ${stage.en.lowercase()}"
            )
    }
}

/**
 * Диалог с автомобилем через адаптер: подключение, чтение параметров, ошибок,
 * VIN. Класс синхронный и ничего не знает ни про потоки, ни про Android -
 * очередь запросов и корутины живут в приложении, а здесь протокол, который
 * можно прогнать в обычном тесте против [com.obdscanner.core.sim.SimulatedEcu].
 */
class ObdSession(
    private val adapter: ElmAdapter,
    /**
     * Какой протокол задать адаптеру. [ObdProtocol.AUTO] - пусть ищет сам;
     * конкретный протокол экономит несколько секунд на подключении, а если он
     * не подойдёт, [connect] всё равно откатится к автоопределению.
     */
    private val preferredProtocol: ObdProtocol = ObdProtocol.AUTO
) : Closeable {

    var adapterId: String = ""
        private set

    var protocol: ObdProtocol? = null
        private set

    var supportedPids: Set<Int> = emptySet()
        private set

    /**
     * Инициализация. Тайминги подобраны по даташиту ELM327: после ATZ адаптер
     * перезагружается примерно секунду, а первый запрос данных на незнакомой
     * машине уходит в поиск протокола и может занять до пяти секунд - поэтому
     * у него отдельный, большой таймаут.
     */
    fun connect(onStage: (ConnectStage) -> Unit = {}): ConnectOutcome {
        onStage(ConnectStage.RESET)
        adapter.drain()
        val reset = adapter.request(Elm.RESET, timeoutMillis = 4000)
        adapterId = reset.lines.lastOrNull { it.contains("ELM", ignoreCase = true) }
            ?: reset.lines.lastOrNull().orEmpty()
        if (reset.lines.isEmpty()) {
            return ConnectOutcome.Failed(ConnectStage.RESET, ElmError.TIMEOUT)
        }

        onStage(ConnectStage.CONFIGURE)
        // Эхо и переводы строк выключаем, чтобы разбирать только данные;
        // заголовки выключаем, чтобы длинные ответы (VIN, список ошибок)
        // собирал сам адаптер.
        for (command in listOf(Elm.ECHO_OFF, Elm.LINEFEEDS_OFF, Elm.SPACES_OFF, Elm.HEADERS_OFF)) {
            adapter.request(command, timeoutMillis = 1000)
        }
        // Адаптивный тайминг в обычном режиме, а не в агрессивном (ATAT2):
        // клоны на агрессивном иногда обрывают ответ на середине, а выигрыш в
        // скорости на фоне периода опроса незаметен.
        adapter.request(Elm.ADAPTIVE_TIMING_1, timeoutMillis = 1000)
        adapter.request(Elm.setProtocol(preferredProtocol), timeoutMillis = 1000)

        onStage(ConnectStage.PROTOCOL)
        // Первый запрос - он же поиск протокола. Одна повторная попытка: на части
        // машин первый запрос после ATZ уходит в пустоту, пока шина просыпается.
        var pidsReply = adapter.request(Elm.request(0x01, 0x00), timeoutMillis = 9000)
        if (pidsReply.isError || ObdPayload.data(pidsReply, 0x01, 0x00) == null) {
            pidsReply = adapter.request(Elm.request(0x01, 0x00), timeoutMillis = 9000)
        }
        // Заданный вручную протокол может не подойти (не та машина, не тот
        // адаптер) - тогда откатываемся к автоопределению, вместо того чтобы
        // сказать "нет связи" и оставить человека наедине с настройкой.
        if (preferredProtocol != ObdProtocol.AUTO &&
            ObdPayload.data(pidsReply, 0x01, 0x00) == null
        ) {
            adapter.request(Elm.PROTOCOL_AUTO, timeoutMillis = 1000)
            pidsReply = adapter.request(Elm.request(0x01, 0x00), timeoutMillis = 9000)
        }
        val firstRange = ObdPayload.data(pidsReply, 0x01, 0x00, expectedBytes = 4)
            ?: return ConnectOutcome.Failed(
                ConnectStage.PROTOCOL,
                pidsReply.error ?: ElmError.UNABLE_TO_CONNECT
            )

        protocol = ObdProtocol.fromDpn(adapter.request(Elm.DESCRIBE_PROTOCOL_NUMBER, 1000).firstLine)

        onStage(ConnectStage.SCAN_PIDS)
        val supported = LinkedHashSet(PidSupport.decode(0x00, firstRange))
        // Следующие карты спрашиваем только если предыдущая обещала продолжение -
        // иначе каждый опрос тратит до шести лишних запросов.
        var base = 0x00
        while (PidSupport.hasNextRange(base, supported)) {
            base += 0x20
            val reply = adapter.request(Elm.request(0x01, base), timeoutMillis = 2000)
            val data = ObdPayload.data(reply, 0x01, base, expectedBytes = 4) ?: break
            supported.addAll(PidSupport.decode(base, data))
        }
        supportedPids = supported
        onStage(ConnectStage.DONE)
        return ConnectOutcome.Connected(adapterId, protocol, supportedPids)
    }

    /** Поддерживает ли машина этот PID (по картам, прочитанным при подключении). */
    fun supports(pid: Int): Boolean = pid in supportedPids

    /** Одно значение режима 01. null - не поддерживается, нет данных или мусор в ответе. */
    fun read(pid: ObdPid): Double? {
        val reply = adapter.request(pid.request)
        val data = ObdPayload.data(reply, 0x01, pid.id, expectedBytes = pid.dataBytes) ?: return null
        return pid.decode(data)
    }

    /** Несколько значений подряд; PID, на которые машина не ответила, в результат не попадают. */
    /**
     * Читает набор параметров за минимальное число обменов с адаптером.
     *
     * Сначала пробуем пакетный запрос: шесть параметров одной командой вместо
     * шести команд. Это единственное, что реально ускоряет опрос - каждый
     * обмен стоит 25-60 мс кругом через адаптер, шину и ЭБУ, и на шести
     * приборах по одному набегает треть секунды.
     *
     * Пакетный запрос понимают не все: часть дешёвых клонов ELM327 отвечает на
     * него мусором, а некоторые блоки - отказом. Поэтому при первой же неудаче
     * режим выключается до конца сессии и дальше всё читается по одному: лучше
     * медленно, чем неправильно.
     */
    fun readAll(pids: List<ObdPid>): Map<Int, Double> {
        val out = LinkedHashMap<Int, Double>()
        val missing = ArrayList<ObdPid>()

        if (multiPidWorks && pids.size > 1) {
            var gotAnything = false
            for (chunk in pids.chunked(Elm.MAX_PIDS_PER_REQUEST)) {
                val raw = readMulti(chunk)
                if (raw.isNotEmpty()) gotAnything = true
                for (pid in chunk) {
                    val bytes = raw[pid.id]
                    if (bytes == null) missing.add(pid) else pid.decode(bytes)?.let { out[pid.id] = it }
                }
            }
            if (!gotAnything) {
                // Ни одного ответа на пакетный запрос - этот адаптер или блок
                // его не умеет. Второй раз не пробуем.
                multiPidWorks = false
                missing.clear()
                missing.addAll(pids)
            }
        } else {
            missing.addAll(pids)
        }

        for (pid in missing) read(pid)?.let { out[pid.id] = it }
        return out
    }

    /** Поддерживает ли эта связка адаптер+блок пакетный запрос. */
    val multiPidSupported: Boolean get() = multiPidWorks

    private fun readMulti(pids: List<ObdPid>): Map<Int, IntArray> {
        val reply = adapter.request(Elm.request(0x01, pids.map { it.id }), timeoutMillis = 1500)
        return ObdPayload.multi(reply, 0x01, pids)
    }

    /** Сырые байты ответа - нужны статусу тестов и диагностическому терминалу. */
    fun readRaw(service: Int, pid: Int?, expectedBytes: Int? = null, timeoutMillis: Long = 1200): IntArray? {
        val reply = adapter.request(Elm.request(service, pid), timeoutMillis)
        return ObdPayload.data(reply, service, pid, expectedBytes)
    }

    fun readMonitorStatus(): MonitorStatus? =
        readRaw(0x01, Pids.MONITOR_STATUS.id, expectedBytes = 4)?.let { MonitorStatus.decode(it) }

    /** Коды неисправностей. Таймаут больше обычного: ответ многокадровый. */
    fun readDtcs(source: DtcSource): List<Dtc> {
        val reply = adapter.request(Elm.request(source.service), timeoutMillis = 3000)
        if (reply.isError) return emptyList()
        return DtcCodec.parse(ObdPayload.allFrameData(reply, source.service), source)
    }

    /**
     * Стирает сохранённые ошибки и гасит Check Engine (режим 04).
     *
     * Заодно обнуляются данные готовности бортовых тестов - после сброса машина
     * какое-то время "не готова" к техосмотру. Постоянные коды (режим 0A) этим
     * не стираются: их гасит только сам ЭБУ, когда убедится, что неисправности
     * больше нет.
     */
    fun clearDtcs(): Boolean {
        val reply = adapter.request(Elm.request(Elm.Service.CLEAR_DTC), timeoutMillis = 5000)
        if (reply.isError) return false
        return ObdPayload.bytes(reply).contains(0x44)
    }

    /** VIN (режим 09, PID 02). */
    fun readVin(): String? {
        val data = readRaw(0x09, 0x02, timeoutMillis = 3000) ?: return null
        val vin = VinDecoder.fromBytes(data)
        return vin.takeIf { it.length == 17 }
    }

    /** Имя блока управления (режим 09, PID 0A) - обычно "ECM-EngineControl". */
    fun readEcuName(): String? = readAscii(0x0A)

    /** Идентификатор калибровки прошивки (режим 09, PID 04). */
    fun readCalibrationId(): String? = readAscii(0x04)

    private fun readAscii(pid: Int): String? {
        val data = readRaw(0x09, pid, timeoutMillis = 3000) ?: return null
        val text = data.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()
        return text.takeIf { it.isNotEmpty() }
    }

    /**
     * Стоп-кадр (режим 02) - значения параметров в момент, когда ЭБУ записал
     * ошибку. Запрос трёхбайтный: сервис, PID и номер кадра (всегда 00, других
     * кадров стандарт для OBD-II не определяет), и этот же номер ЭБУ повторяет
     * в ответе перед данными - его и отбрасываем.
     */
    fun readFreezeFrame(pids: List<ObdPid>): Map<Int, Double> {
        val out = LinkedHashMap<Int, Double>()
        for (pid in pids) {
            val reply = adapter.request("02%02X00".format(pid.id), timeoutMillis = 2000)
            var data = ObdPayload.data(reply, 0x02, pid.id) ?: continue
            if (data.size > pid.dataBytes && data[0] == 0x00) data = data.copyOfRange(1, data.size)
            if (data.size > pid.dataBytes) data = data.copyOfRange(0, pid.dataBytes)
            pid.decode(data)?.let { out[pid.id] = it }
        }
        return out
    }

    /**
     * Чтение пользовательского параметра: режим 01 или 22 с формулой, которую
     * вписал человек. Режим 22 стандартом описан, но НАБОР параметров в нём
     * задаёт производитель, поэтому "нет данных" здесь - обычный ответ, а не
     * признак поломки.
     */
    fun readCustom(pid: CustomPid): Double? {
        if (!pid.valid) return null
        val reply = adapter.request(pid.request, timeoutMillis = 2000)
        val data = ObdPayload.data(reply, pid.mode, pid.echoBytes, pid.dataBytes) ?: return null
        return pid.decode(data)
    }

    /**
     * Перебор заводских параметров режима 22.
     *
     * Списка этих номеров для ME17.9.7/ME17.9.71 в открытом виде нет, зато сам
     * блок отвечает честно: на несуществующий параметр приходит отказ, на
     * существующий - значение. Поэтому номер ищется не по форумам, а опросом
     * конкретной машины.
     *
     * Таймаут короткий: большинство номеров не существует, и ждать по секунде
     * на каждый - это часы. Пропущенный из-за этого ответ не страшен, перебор
     * можно повторить.
     *
     * Только чтение. Запись (режим 2E) не делается нигде: оборванная запись
     * превращает блок в кирпич.
     */
    fun scanService22(
        from: Int,
        to: Int,
        shouldStop: () -> Boolean = { false },
        onProgress: (done: Int, total: Int, found: Int) -> Unit = { _, _, _ -> },
    ): List<ScanHit> {
        val range = PidScan.clampRange(from, to)
        val hits = ArrayList<ScanHit>()
        var done = 0
        for (pid in range.from..range.to) {
            if (shouldStop()) break
            readService22(pid)?.let { hits.add(ScanHit(pid, it.toList())) }
            done++
            onProgress(done, range.size, hits.size)
        }
        return hits
    }

    /**
     * Перечитывает уже найденные параметры - для сравнения "до и после".
     * Человек делает снимок, включает фары или обогрев стекла, делает второй,
     * и смотрит, что изменилось: так находится параметр, которого нет ни в
     * одной документации.
     */
    fun readService22(pids: List<Int>, shouldStop: () -> Boolean = { false }): List<ScanHit> {
        val out = ArrayList<ScanHit>(pids.size)
        for (pid in pids) {
            if (shouldStop()) break
            readService22(pid)?.let { out.add(ScanHit(pid, it.toList())) }
        }
        return out
    }

    private fun readService22(pid: Int): IntArray? {
        val reply = adapter.request("22%04X".format(pid), timeoutMillis = SCAN_TIMEOUT_MILLIS)
        if (reply.isError) return null
        val echo = intArrayOf((pid shr 8) and 0xFF, pid and 0xFF)
        return ObdPayload.data(reply, 0x22, echo)?.takeIf { it.isNotEmpty() }
    }

    /** Напряжение на разъёме по версии самого адаптера (`AT RV`), в вольтах. */
    fun adapterVoltage(): Double? {
        val reply = adapter.request(Elm.READ_VOLTAGE, timeoutMillis = 1000)
        val text = reply.firstLine.uppercase().removeSuffix("V").trim()
        return text.toDoubleOrNull()
    }

    /** Произвольная команда - для экрана "Терминал". */
    fun raw(command: String, timeoutMillis: Long = 3000): ElmReply =
        adapter.request(command.trim().uppercase(), timeoutMillis)

    /**
     * Выключается навсегда при первой неудаче пакетного запроса: пересматривать
     * решение посреди поездки незачем, адаптер в ней не меняется.
     */
    private var multiPidWorks = true

    override fun close() = adapter.close()

    private companion object {
        /**
         * Таймаут одного запроса при переборе. Большинство номеров не
         * существует, и на отказ блок отвечает сразу; ждать по секунде на
         * каждый из тысяч номеров - это часы вместо минут.
         */
        const val SCAN_TIMEOUT_MILLIS = 350L
    }
}
