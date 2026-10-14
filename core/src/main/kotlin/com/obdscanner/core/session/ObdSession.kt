package com.obdscanner.core.session

import com.obdscanner.core.dtc.Dtc
import com.obdscanner.core.dtc.DtcCodec
import com.obdscanner.core.dtc.DtcSource
import com.obdscanner.core.elm.AdapterInfo
import com.obdscanner.core.elm.AdapterProbe
import com.obdscanner.core.elm.Elm
import com.obdscanner.core.elm.ElmError
import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.elm.ObdPayload
import com.obdscanner.core.elm.ObdProtocol
import com.obdscanner.core.elm.ObdText
import com.obdscanner.core.modules.FoundModule
import com.obdscanner.core.modules.ModuleReply
import com.obdscanner.core.modules.ModuleScan
import com.obdscanner.core.monitor.MonitorIds
import com.obdscanner.core.monitor.MonitorSupport
import com.obdscanner.core.monitor.MonitorTest
import com.obdscanner.core.monitor.O2SensorLocation
import com.obdscanner.core.monitor.O2SensorTest
import com.obdscanner.core.obd.InUsePerformance
import com.obdscanner.core.obd.Mode09
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
        configureAdapter()
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
            // Одна повторная попытка: если карта диапазона потерялась на
            // полпути, молча теряется и всё, что за ней, - человек потом
            // видит короткий список параметров и думает, что машина их не
            // умеет.
            var data = ObdPayload.data(
                adapter.request(Elm.request(0x01, base), timeoutMillis = 2000),
                0x01,
                base,
                expectedBytes = 4,
            )
            if (data == null) {
                data = ObdPayload.data(
                    adapter.request(Elm.request(0x01, base), timeoutMillis = 2000),
                    0x01,
                    base,
                    expectedBytes = 4,
                )
            }
            if (data == null) break
            supported.addAll(PidSupport.decode(base, data))
        }
        supportedPids = supported
        onStage(ConnectStage.DONE)
        return ConnectOutcome.Connected(adapterId, protocol, supportedPids)
    }

    /**
     * Режим работы адаптера: что он печатает и как ждёт ответа.
     *
     * Вынесено из [connect] отдельно, потому что к тем же настройкам нужно
     * вернуться после перебора блоков - там и заголовок запроса, и фильтр
     * приёма, и тайминги сдвинуты.
     */
    private fun configureAdapter() {
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

        // Параметры, которые машина молча игнорирует, перестаём спрашивать.
        // Это не мелочь: если человек включил в приборы всё подряд, два
        // десятка неподдерживаемых номеров добавляют по обмену каждый и
        // каждый цикл - то есть съедают ровно ту скорость, ради которой
        // быстрые параметры отделены от медленных. Изредка пробуем снова:
        // список поддержки у блока бывает неполным, и тишина может быть
        // разовой.
        if (++cyclesSinceSilentRetry >= SILENT_RETRY_CYCLES) {
            cyclesSinceSilentRetry = 0
            silentPids.clear()
        }
        val asked = pids.filter { (silentPids[it.id] ?: 0) < SILENT_BEFORE_DROP }
        if (asked.isEmpty()) return out

        // Пакет пробуем, пока он работает, и изредка - даже после отказа.
        // Один раз выключить его навсегда нельзя: блок может промолчать на
        // первом цикле, пока просыпается, или в момент пуска двигателя, а
        // расплачиваться за это секундой на кадр до конца поездки незачем.
        val retrying = !multiPidWorks && ++cyclesSinceBatchOff >= BATCH_RETRY_CYCLES
        val tryBatch = asked.size > 1 && (multiPidWorks || retrying)
        var batchAnswered = false

        if (tryBatch) {
            if (retrying) cyclesSinceBatchOff = 0
            val chunks = asked.chunked(Elm.MAX_PIDS_PER_REQUEST)
            // На пробном цикле хватает одной пачки: ответил блок - со
            // следующего цикла пакет снова работает целиком; не ответил -
            // потеряно одно обращение, а не весь цикл.
            val probe = if (retrying) chunks.take(1) else chunks
            for (chunk in probe) {
                val raw = readMulti(chunk)
                if (raw.isNotEmpty()) batchAnswered = true
                for (pid in chunk) {
                    val bytes = raw[pid.id]
                    if (bytes == null) missing.add(pid) else pid.decode(bytes)?.let { out[pid.id] = it }
                }
            }
            if (retrying) missing.addAll(chunks.drop(1).flatten())
        } else {
            missing.addAll(asked)
        }

        for (pid in missing) read(pid)?.let { out[pid.id] = it }

        // Считаем молчание только когда связь жива: если не ответил никто,
        // виновата шина, а не конкретный параметр.
        if (out.isNotEmpty()) {
            for (pid in asked) {
                if (pid.id in out) silentPids.remove(pid.id)
                else silentPids[pid.id] = (silentPids[pid.id] ?: 0) + 1
            }
        }

        if (tryBatch) {
            if (batchAnswered) {
                multiPidWorks = true
                batchFailures = 0
            } else if (out.isNotEmpty()) {
                // Пакет промолчал, а по одному параметры пришли - дело именно в
                // пакетном запросе. Один такой цикл ещё ничего не значит,
                // несколько подряд - уже не случайность.
                if (++batchFailures >= BATCH_FAILURES_ALLOWED) multiPidWorks = false
            }
            // Молчит и пакет, и поштучное чтение - это обрыв связи, и пакетный
            // режим тут ни при чём: выключать его было бы наказанием не за то.
        }
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
    fun readDtcs(source: DtcSource): List<Dtc> = readDtcsOrNull(source).orEmpty()

    /**
     * Коды ошибок; null - прочитать не удалось.
     *
     * Разница важнее, чем кажется: пустой список означает "ЭБУ ответил, кодов
     * нет", а null - "ответа не было". Раньше и то и другое превращалось в
     * пустой список, и экран бодро сообщал, что машина чистая, хотя на самом
     * деле её просто не спросили.
     *
     * "NO DATA" при этом считается ответом: на запрос кодов блок так и молчит,
     * когда сохранять нечего, - особенно на режим 0A, которого у многих машин
     * нет вовсе. А вот обрыв, ошибка шины или молчание адаптера - это неудача.
     */
    fun readDtcsOrNull(source: DtcSource): List<Dtc>? {
        val reply = adapter.request(Elm.request(source.service), timeoutMillis = 3000)
        reply.error?.let { return if (it == ElmError.NO_DATA) emptyList() else null }
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

    // --- режимы 05, 06 и 09: бортовые тесты и прошивка ---------------------

    /**
     * Какие мониторы (MID) отдаёт режим 06. Карты запрашиваются по той же
     * схеме, что и карты PID, и ровно до первой, которая не обещает следующую:
     * восемь запросов вслепую - это полсекунды на пустом месте.
     */
    fun readSupportedMonitorIds(): Set<Int> {
        val out = LinkedHashSet<Int>()
        for (base in MonitorSupport.RANGE_MIDS) {
            val data = readMonitorPayload(base) ?: break
            // Карта поддержки - это 4 байта следом за эхом MID.
            if (data.size < 5) break
            out.addAll(MonitorSupport.decode(base, data.copyOfRange(1, 5)))
            if (!MonitorSupport.hasNextRange(base, out)) break
        }
        return out.filterNot { MonitorIds.isSupportMap(it) }.toSet()
    }

    /**
     * Результаты тестов одного монитора (CAN) либо одного теста (старые шины).
     *
     * Ответ разбирается вместе с эхом номера: в одном сообщении по CAN
     * приходит несколько записей, и у каждой свой номер монитора - отбросив
     * первый как "эхо", мы сдвинули бы все записи на байт.
     */
    fun readMonitorTests(mid: Int): List<MonitorTest> {
        val payload = readMonitorPayload(mid) ?: return emptyList()
        val can = protocol?.isCan ?: true
        return if (can) {
            MonitorTest.decodeCan(payload)
        } else {
            listOfNotNull(MonitorTest.decodeLegacy(payload))
        }
    }

    private fun readMonitorPayload(mid: Int): IntArray? {
        val reply = adapter.request(Elm.request(Elm.Service.MONITOR_TESTS, mid), timeoutMillis = 3000)
        if (reply.isError) return null
        val frames = ObdPayload.allFrameData(reply, Elm.Service.MONITOR_TESTS)
        if (frames.isEmpty()) return null
        // Несколько блоков на шине отвечают каждый своим кадром; записи просто
        // складываются в общий список, как это и показывают на экране.
        val merged = ArrayList<Int>()
        frames.forEach { merged.addAll(it.toList()) }
        return merged.toIntArray().takeIf { it.isNotEmpty() }
    }

    /**
     * Все результаты режима 06 подряд. Запросов столько, сколько мониторов
     * объявил блок, поэтому [shouldStop] обязателен: на машине с четырьмя
     * банками их больше тридцати.
     */
    fun readAllMonitorTests(shouldStop: () -> Boolean = { false }): List<MonitorTest> {
        val out = ArrayList<MonitorTest>()
        for (mid in readSupportedMonitorIds()) {
            if (shouldStop()) break
            out.addAll(readMonitorTests(mid))
        }
        return out
    }

    /**
     * Где стоят лямбда-зонды. PID 0x13 и 0x1D - это две разные раскладки одного
     * байта, и машина поддерживает ровно одну; какую, видно по карте PID.
     */
    fun readO2SensorLocations(): List<O2SensorLocation> {
        if (supports(0x13)) {
            val data = readRaw(0x01, 0x13, expectedBytes = 1) ?: return emptyList()
            return O2SensorLocation.decodeTwoBanks(data.firstOrNull() ?: return emptyList())
        }
        if (supports(0x1D)) {
            val data = readRaw(0x01, 0x1D, expectedBytes = 1) ?: return emptyList()
            return O2SensorLocation.decodeFourBanks(data.firstOrNull() ?: return emptyList())
        }
        return emptyList()
    }

    /**
     * Какие тесты режима 05 блок отдаёт. Пустой ответ - не "ничего нет", а
     * "карту не прислали"; тогда остаётся перебрать номера из стандарта.
     */
    fun readSupportedO2Tests(): Set<Int> {
        val reply = adapter.request("0500", timeoutMillis = 2000)
        if (reply.isError) return emptySet()
        val payload = ObdPayload.allFrameData(reply, Elm.Service.O2_TESTS).firstOrNull() ?: return emptySet()
        if (payload.size < 5) return emptySet()
        return PidSupport.decode(0x00, payload.copyOfRange(1, 5))
    }

    /**
     * Результаты тестов одного лямбда-зонда (режим 05).
     *
     * [sensorMask] - бит датчика, а не его номер: 0x01 - первый датчик первого
     * банка, 0x10 - первый датчик второго. Передав "2" вместо 0x02, получишь
     * ответ про другой датчик, и блок об этом не предупредит.
     */
    fun readO2SensorTests(sensorMask: Int, tids: List<Int> = O2SensorTest.TIDS): List<O2SensorTest> {
        val out = ArrayList<O2SensorTest>()
        for (tid in tids) {
            val reply = adapter.request("05%02X%02X".format(tid, sensorMask), timeoutMillis = 1500)
            if (reply.isError) continue
            val payload = ObdPayload.allFrameData(reply, Elm.Service.O2_TESTS).firstOrNull() ?: continue
            O2SensorTest.decode(payload)?.let { out.add(it) }
        }
        return out
    }

    /** Какие PID отдаёт режим 09 (данные об автомобиле). */
    fun readVehicleInfoSupport(): Set<Int> {
        val data = readRaw(Mode09.SERVICE, Mode09.SUPPORTED, expectedBytes = 4, timeoutMillis = 2000)
            ?: return emptySet()
        if (data.size < 4) return emptySet()
        return PidSupport.decode(0x00, data)
    }

    /**
     * Все идентификаторы калибровки, а не первый: у современных блоков их
     * два-три, по одному на программный модуль.
     */
    fun readCalibrationIds(): List<String> {
        val data = readRaw(Mode09.SERVICE, Mode09.CALID, timeoutMillis = 3000) ?: return emptyList()
        return Mode09.calibrationIds(data)
    }

    /**
     * Контрольные суммы калибровок (CVN). Это не текст, а четыре байта на
     * каждую калибровку: заводской CALID с неподходящим CVN - признак
     * правленой прошивки.
     */
    fun readCalibrationVerificationNumbers(): List<String> {
        val data = readRaw(Mode09.SERVICE, Mode09.CVN, timeoutMillis = 3000) ?: return emptyList()
        return Mode09.calibrationVerificationNumbers(data)
    }

    /**
     * Счётчики наработки бортовых тестов. У бензина и дизеля это разные PID с
     * разным набором счётчиков, поэтому тип двигателя нужно знать заранее - его
     * сообщает статус тестов (режим 01 PID 01).
     */
    fun readInUsePerformance(compressionIgnition: Boolean): InUsePerformance? {
        val pid = if (compressionIgnition) Mode09.IPT_COMPRESSION else Mode09.IPT_SPARK
        val data = readRaw(Mode09.SERVICE, pid, timeoutMillis = 3000) ?: return null
        val result = InUsePerformance.decode(data, compressionIgnition)
        return result.takeIf { it.counters.isNotEmpty() }
    }

    /**
     * Код, из-за которого блок записал стоп-кадр (режим 02, PID 02).
     *
     * Без него стоп-кадр наполовину бесполезен: на машине с тремя ошибками
     * видно обстановку в момент записи, но не видно, какая из трёх её
     * записала. Пара нулей означает "кадра нет" - это не код P0000.
     */
    fun readFreezeFrameDtc(): String? {
        val reply = adapter.request("020200", timeoutMillis = 2000)
        if (reply.isError) return null
        var data = ObdPayload.data(reply, 0x02, 0x02) ?: return null
        if (data.size > 2 && data[0] == 0x00) data = data.copyOfRange(1, data.size)
        if (data.size < 2) return null
        if (data[0] == 0 && data[1] == 0) return null
        return DtcCodec.decode(data[0], data[1])
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
    // --- блоки вне OBD-II (ABS, подушки, АКПП) -----------------------------

    /**
     * Можно ли вообще искать другие блоки.
     *
     * Адресация блоков, которую мы умеем, - это 11-битная CAN: запрос уходит
     * на 0x7xx, ответ приходит на восемь больше. На K-line и 29-битной CAN
     * адреса устроены иначе, и честнее сказать "не умею", чем слать команды
     * наугад.
     */
    val canScanModules: Boolean
        get() = protocol == ObdProtocol.ISO15765_11_500 ||
            protocol == ObdProtocol.ISO15765_11_250 ||
            protocol == ObdProtocol.USER1 ||
            protocol == ObdProtocol.USER2

    /**
     * Обходит диагностические адреса и собирает те, где кто-то живёт.
     *
     * Это единственный способ увидеть ошибку ABS: стандартные режимы 03/07/0A
     * спрашивают только двигатель, и лампа ABS на панели для них не
     * существует. Адреса блоков производитель не публикует, поэтому их
     * приходится искать перебором - как это делают все сканеры.
     *
     * Опрос только читает. Сервис 19/18 ничего в блоке не меняет, а стирания
     * чужих кодов в приложении нет: прерванная запись в блок ABS чинится уже
     * не приложением.
     */
    fun scanModules(
        from: Int = ModuleScan.FIRST_ADDRESS,
        to: Int = ModuleScan.LAST_ADDRESS,
        shouldStop: () -> Boolean = { false },
        onProgress: (done: Int, total: Int, found: Int) -> Unit = { _, _, _ -> },
    ): List<FoundModule> {
        if (!canScanModules) return emptyList()
        val first = from.coerceIn(ModuleScan.FIRST_ADDRESS, ModuleScan.LAST_ADDRESS)
        val last = to.coerceIn(first, ModuleScan.LAST_ADDRESS)
        val found = ArrayList<FoundModule>()
        var done = 0
        val total = last - first + 1
        // Фильтр приёма на весь диапазон 0x700-0x7FF: иначе адаптер отдаёт
        // только ответы блока двигателя и чужие просто не доходят. Если клон
        // не понимает маску с "X", фильтр ставится на каждый адрес отдельно.
        val wideFilter = adapter.request("ATCRA 7XX", timeoutMillis = 1000).isOk
        try {
            // Адаптивный тайминг на переборе только мешает: на молчащем адресе
            // он растягивает ожидание, а адресов больше двух сотен.
            adapter.request(Elm.ADAPTIVE_TIMING_OFF, timeoutMillis = 500)
            adapter.request(Elm.timeout(MODULE_TIMEOUT_MILLIS), timeoutMillis = 500)
            for (address in first..last) {
                if (shouldStop()) break
                readModule(address, wideFilter)?.let { found.add(FoundModule(address, it)) }
                done++
                onProgress(done, total, found.size)
            }
        } finally {
            restoreStandardAddressing()
        }
        return found
    }

    /** Перечитать коды одного блока - после ремонта, не гоняя весь перебор. */
    fun readModule(address: Int): FoundModule? {
        if (!canScanModules) return null
        val wideFilter = adapter.request("ATCRA 7XX", timeoutMillis = 1000).isOk
        return try {
            adapter.request(Elm.timeout(MODULE_TIMEOUT_MILLIS), timeoutMillis = 500)
            readModule(address, wideFilter)?.let { FoundModule(address, it) }
        } finally {
            restoreStandardAddressing()
        }
    }

    private fun readModule(address: Int, wideFilter: Boolean): ModuleReply? {
        // Длинный список кодов приходит несколькими кадрами, и адаптер должен
        // подтвердить приём первого. Отдельно это настраивать не надо:
        // подтверждение он шлёт с текущего заголовка, а это как раз адрес
        // блока, который мы спрашиваем.
        adapter.request("ATSH%03X".format(address), timeoutMillis = 500)
        if (!wideFilter) {
            adapter.request("ATCRA%03X".format(ModuleScan.responseFor(address)), timeoutMillis = 500)
        }
        val uds = ModuleScan.parse(
            ObdPayload.frames(adapter.request(ModuleScan.UDS_READ_DTC, MODULE_REPLY_MILLIS))
        )
        // Блок ответил кодами - дальше спрашивать нечего.
        if (uds is ModuleReply.Codes) return uds
        // Отказ "такого сервиса нет" означает, что блок есть, но говорит на
        // старом KWP2000. Это единственный случай, когда стоит спросить второй
        // раз: в остальном второй запрос удвоил бы время перебора.
        if (uds is ModuleReply.Refused && uds.nrc in SERVICE_UNKNOWN) {
            val kwp = ModuleScan.parse(
                ObdPayload.frames(adapter.request(ModuleScan.KWP_READ_DTC, MODULE_REPLY_MILLIS))
            )
            if (kwp is ModuleReply.Codes) return kwp
        }
        return uds
    }

    /**
     * Вернуть адресацию к обычному OBD-II.
     *
     * Без этого после перебора приборы перестают читаться: адаптер продолжает
     * слать запросы на адрес последнего опрошенного блока.
     */
    private fun restoreStandardAddressing() {
        adapter.request("ATCRA", timeoutMillis = 500)
        adapter.request("ATSH7DF", timeoutMillis = 500)
        adapter.request(Elm.ADAPTIVE_TIMING_1, timeoutMillis = 500)
        adapter.request(Elm.timeout(DEFAULT_TIMEOUT_MILLIS), timeoutMillis = 500)
        // Проверка, что обычный опрос снова работает. Если нет - полная
        // настройка заново: лучше потерять секунду, чем оставить человека с
        // замершими приборами и думать, что это адаптер отвалился.
        if (read(Pids.RPM) == null && read(Pids.COOLANT_TEMP) == null) {
            configureAdapter()
        }
    }

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

    /**
     * Опознание адаптера и проверка команд, на которых держатся функции
     * приложения.
     *
     * После проверки настройки восстанавливаются: проверка задаёт свой
     * заголовок запроса и фильтр приёма, и оставить адаптер в таком виде
     * нельзя - следующий же опрос ушёл бы одному блоку вместо всех.
     */
    fun checkAdapter(): AdapterInfo {
        val info = AdapterProbe { command -> adapter.request(command, timeoutMillis = 1500) }.check()
        configureAdapter()
        // Заголовок запроса проверка сдвинула на конкретный блок - возвращаем
        // широковещательный, которым и спрашивается OBD-II.
        if (protocol?.isCan != false) {
            adapter.request("ATSH7DF", timeoutMillis = 1000)
        }
        return info
    }

    /**
     * Записать поправку вольтметра в сам адаптер (`AT CV`).
     *
     * Отличие от поправки в настройках приложения: записанная в адаптер, она
     * действует для всех программ и переживает переустановку, но хранится в
     * железке - и на чужом адаптере её не будет. Поэтому приложение умеет оба
     * способа и не навязывает ни один.
     */
    fun calibrateAdapterVoltage(volts: Double): Boolean =
        adapter.request(Elm.calibrateVoltage(volts), timeoutMillis = 2000).isOk

    /** Вернуть заводскую калибровку вольтметра адаптера. */
    fun resetAdapterVoltageCalibration(): Boolean =
        adapter.request(Elm.RESET_VOLTAGE_CALIBRATION, timeoutMillis = 2000).isOk

    /**
     * Короткий запрос, чтобы связь не уснула.
     *
     * Клоны ELM327 закрывают канал Bluetooth сами, если в него минуту ничего
     * не шлют, и следующий запрос уходит в уже мёртвый сокет - приложение при
     * этом выглядит зависшим. Поэтому в паузах (экран ошибок открыт, человек
     * читает) раз в пару секунд спрашивается напряжение: ответ не нужен, нужен
     * сам обмен.
     */
    fun keepAlive(): Boolean = !adapter.request(Elm.READ_VOLTAGE, timeoutMillis = 1000).isError

    /** Произвольная команда - для экрана "Терминал". */
    fun raw(command: String, timeoutMillis: Long = 3000): ElmReply =
        adapter.request(command.trim().uppercase(), timeoutMillis)

    /**
     * Выключается навсегда при первой неудаче пакетного запроса: пересматривать
     * решение посреди поездки незачем, адаптер в ней не меняется.
     */
    private var multiPidWorks = true

    /** Сколько циклов подряд пакет промолчал там, где поштучно всё читается. */
    private var batchFailures = 0

    /** Сколько циклов прошло с отказа от пакета - через BATCH_RETRY_CYCLES пробуем снова. */
    private var cyclesSinceBatchOff = 0

    /** Сколько раз подряд параметр промолчал там, где остальные ответили. */
    private val silentPids = HashMap<Int, Int>()

    private var cyclesSinceSilentRetry = 0

    /**
     * Параметры, которые машина не отдаёт и которые поэтому больше не
     * спрашиваются. Экран показывает по ним не голый прочерк, а причину.
     */
    val unansweredPids: Set<Int>
        get() = silentPids.filterValues { it >= SILENT_BEFORE_DROP }.keys

    override fun close() = adapter.close()

    private companion object {
        /**
         * Сколько адаптер ждёт ответа на переборе блоков. Молчащих адресов
         * больше двух сотен, и каждые лишние 50 мс - это ещё десять секунд
         * ожидания с телефоном в руках.
         */
        const val MODULE_TIMEOUT_MILLIS = 100

        /** Свой таймаут чуть больше адаптерного - чтобы успеть прочитать ответ. */
        const val MODULE_REPLY_MILLIS = 900L

        /** Таймаут шины в обычном режиме, к нему возвращаемся после перебора. */
        const val DEFAULT_TIMEOUT_MILLIS = 200

        /**
         * Отказы в духе "такого сервиса у меня нет" - значит блок есть, но
         * говорит на другом языке, и стоит спросить его по-старому (KWP2000).
         */
        val SERVICE_UNKNOWN = setOf(0x11, 0x12, 0x7E, 0x7F)

        /**
         * Столько неудач пакетного запроса подряд ещё списываем на занятый
         * блок. Меньше нельзя: один промах на пуске двигателя переводил весь
         * опрос в поштучный режим до конца поездки.
         */
        const val BATCH_FAILURES_ALLOWED = 3

        /** Через столько циклов после отказа пакет пробуется снова. */
        const val BATCH_RETRY_CYCLES = 120

        /**
         * Столько раз подряд параметр должен промолчать, чтобы его перестали
         * спрашивать. Один-два пропуска бывают и у поддерживаемого номера.
         */
        const val SILENT_BEFORE_DROP = 3

        /** Через столько циклов молчуны пробуются заново. */
        const val SILENT_RETRY_CYCLES = 200

        /**
         * Таймаут одного запроса при переборе. Большинство номеров не
         * существует, и на отказ блок отвечает сразу; ждать по секунде на
         * каждый из тысяч номеров - это часы вместо минут.
         */
        const val SCAN_TIMEOUT_MILLIS = 350L
    }
}
