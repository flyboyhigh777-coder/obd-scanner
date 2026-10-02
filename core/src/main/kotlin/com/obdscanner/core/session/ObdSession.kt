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
import com.obdscanner.core.obd.MonitorStatus
import com.obdscanner.core.obd.ObdPid
import com.obdscanner.core.obd.PidSupport
import com.obdscanner.core.obd.Pids
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
class ObdSession(private val adapter: ElmAdapter) : Closeable {

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
        adapter.request(Elm.ADAPTIVE_TIMING_2, timeoutMillis = 1000)
        adapter.request(Elm.PROTOCOL_AUTO, timeoutMillis = 1000)

        onStage(ConnectStage.PROTOCOL)
        // Первый запрос - он же поиск протокола. Одна повторная попытка: на части
        // машин первый запрос после ATZ уходит в пустоту, пока шина просыпается.
        var pidsReply = adapter.request(Elm.request(0x01, 0x00), timeoutMillis = 9000)
        if (pidsReply.isError || ObdPayload.data(pidsReply, 0x01, 0x00) == null) {
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
    fun readAll(pids: List<ObdPid>): Map<Int, Double> {
        val out = LinkedHashMap<Int, Double>()
        for (pid in pids) {
            read(pid)?.let { out[pid.id] = it }
        }
        return out
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

    /** Напряжение на разъёме по версии самого адаптера (`AT RV`), в вольтах. */
    fun adapterVoltage(): Double? {
        val reply = adapter.request(Elm.READ_VOLTAGE, timeoutMillis = 1000)
        val text = reply.firstLine.uppercase().removeSuffix("V").trim()
        return text.toDoubleOrNull()
    }

    /** Произвольная команда - для экрана "Терминал". */
    fun raw(command: String, timeoutMillis: Long = 3000): ElmReply =
        adapter.request(command.trim().uppercase(), timeoutMillis)

    override fun close() = adapter.close()
}
