package com.obdscanner.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obdscanner.app.data.AppSettings
import com.obdscanner.app.data.ExportResult
import com.obdscanner.app.data.TripExport
import com.obdscanner.app.transport.BluetoothDevices
import com.obdscanner.app.transport.BluetoothObdChannel
import com.obdscanner.app.transport.ConnectionTarget
import com.obdscanner.app.transport.DemoObdChannel
import com.obdscanner.app.transport.WifiObdChannel
import com.obdscanner.core.dtc.Dtc
import com.obdscanner.core.dtc.DtcSource
import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.elm.ObdProtocol
import com.obdscanner.core.obd.FuelEconomy
import com.obdscanner.core.obd.FuelType
import com.obdscanner.core.obd.MonitorStatus
import com.obdscanner.core.obd.ObdPid
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.obd.UnitSystem
import com.obdscanner.core.obd.VinDecoder
import com.obdscanner.core.obd.VinInfo
import com.obdscanner.core.session.ConnectOutcome
import com.obdscanner.core.session.ConnectStage
import com.obdscanner.core.session.ElmAdapter
import com.obdscanner.core.session.ObdChannel
import com.obdscanner.core.session.ObdSession
import com.obdscanner.core.trip.TripRecorder
import com.obdscanner.core.trip.TripStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние связи с адаптером. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val stage: ConnectStage) : ConnectionState
    data class Connected(
        val target: ConnectionTarget,
        val adapterId: String,
        val protocol: ObdProtocol?,
        val pidCount: Int
    ) : ConnectionState

    data class Failed(val message: String) : ConnectionState
}

/** Последние прочитанные значения плюс то, что из них считается. */
data class LiveSnapshot(
    val values: Map<Int, Double> = emptyMap(),
    val litresPerHour: Double? = null,
    val litresPer100Km: Double? = null,
    val monitor: MonitorStatus? = null,
    val updatedAtMillis: Long = 0
) {
    val hasData: Boolean get() = values.isNotEmpty()
}

/** Ошибки: три режима чтения, стоп-кадр и бортовые тесты. */
data class DtcState(
    val reading: Boolean = false,
    val loaded: Boolean = false,
    val stored: List<Dtc> = emptyList(),
    val pending: List<Dtc> = emptyList(),
    val permanent: List<Dtc> = emptyList(),
    val monitor: MonitorStatus? = null,
    val freezeFrame: Map<Int, Double> = emptyMap(),
    val message: String? = null
) {
    val total: Int get() = stored.size + pending.size + permanent.size
}

/** Данные об автомобиле - читаются один раз при подключении. */
data class VehicleState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val vin: String? = null,
    val vinInfo: VinInfo? = null,
    val ecuName: String? = null,
    val calibrationId: String? = null,
    val adapterVoltage: Double? = null,
    val obdStandard: Int? = null,
    val fuelType: Int? = null,
    val counters: Map<Int, Double> = emptyMap()
)

data class TripState(
    val recording: Boolean = false,
    val stats: TripStats = TripRecorder().stats(),
    val exportedUri: Uri? = null
)

/** Что умеет попросить интерфейс у рабочего потока. */
private sealed interface ObdTask {
    data object ReadDtcs : ObdTask
    data object ClearDtcs : ObdTask
    data object RefreshVehicle : ObdTask
    data class Terminal(val command: String) : ObdTask
}

/**
 * Хозяин сессии с автомобилем.
 *
 * Весь разговор с адаптером идёт в одной корутине на [Dispatchers.IO]: адаптер
 * ELM327 последовательный, он отвечает на одну команду за раз, и два
 * одновременных запроса (опрос приборки и чтение ошибок) просто перемешали бы
 * ответы. Поэтому интерфейс не вызывает сессию напрямую, а кладёт задание в
 * [tasks], а рабочий цикл между опросами разбирает очередь.
 */
class ObdViewModel(application: Application) : AndroidViewModel(application) {

    val settings = AppSettings(application)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _live = MutableStateFlow(LiveSnapshot())
    val live: StateFlow<LiveSnapshot> = _live.asStateFlow()

    private val _dtc = MutableStateFlow(DtcState())
    val dtc: StateFlow<DtcState> = _dtc.asStateFlow()

    private val _vehicle = MutableStateFlow(VehicleState())
    val vehicle: StateFlow<VehicleState> = _vehicle.asStateFlow()

    private val _trip = MutableStateFlow(TripState())
    val trip: StateFlow<TripState> = _trip.asStateFlow()

    private val _terminal = MutableStateFlow<List<String>>(emptyList())
    val terminal: StateFlow<List<String>> = _terminal.asStateFlow()

    /** Параметры, которые эта машина реально отдаёт. */
    private val _supportedPids = MutableStateFlow<List<ObdPid>>(emptyList())
    val supportedPids: StateFlow<List<ObdPid>> = _supportedPids.asStateFlow()

    private val _dashboardPids = MutableStateFlow(settings.dashboardPids)
    val dashboardPids: StateFlow<List<Int>> = _dashboardPids.asStateFlow()

    private val _units = MutableStateFlow(settings.units)
    val units: StateFlow<UnitSystem> = _units.asStateFlow()

    private val _pollInterval = MutableStateFlow(settings.pollIntervalMillis)
    val pollInterval: StateFlow<Int> = _pollInterval.asStateFlow()

    private val tasks = Channel<ObdTask>(Channel.UNLIMITED)
    private var worker: Job? = null

    /**
     * Запись поездки живёт дольше соединения (её смотрят и после того, как
     * заглушили двигатель), а трогают её и рабочая корутина, и интерфейс при
     * выгрузке - отсюда блокировка.
     */
    private val recorder = TripRecorder()
    private val recorderLock = Any()
    @Volatile private var recording = false
    @Volatile private var dieselFuel = false

    // --- управление из интерфейса ------------------------------------------

    fun connect(target: ConnectionTarget) {
        worker?.cancel()
        _connection.value = ConnectionState.Connecting(ConnectStage.RESET)
        _live.value = LiveSnapshot()
        _dtc.value = DtcState()
        _vehicle.value = VehicleState()
        _terminal.value = emptyList()
        if (target is ConnectionTarget.Bluetooth) settings.lastBluetoothAddress = target.address
        worker = viewModelScope.launch(Dispatchers.IO) { runSession(target) }
    }

    fun disconnect() {
        worker?.cancel()
        worker = null
        recording = false
        _connection.value = ConnectionState.Disconnected
        _trip.value = _trip.value.copy(recording = false)
    }

    fun readDtcs() {
        _dtc.value = _dtc.value.copy(reading = true, message = null)
        tasks.trySend(ObdTask.ReadDtcs)
    }

    fun clearDtcs() {
        _dtc.value = _dtc.value.copy(reading = true, message = null)
        tasks.trySend(ObdTask.ClearDtcs)
    }

    fun refreshVehicle() {
        _vehicle.value = _vehicle.value.copy(loading = true)
        tasks.trySend(ObdTask.RefreshVehicle)
    }

    fun sendTerminalCommand(command: String) {
        if (command.isBlank()) return
        tasks.trySend(ObdTask.Terminal(command))
    }

    fun setPollInterval(millis: Int) {
        settings.pollIntervalMillis = millis
        _pollInterval.value = settings.pollIntervalMillis
    }

    fun setUnits(system: UnitSystem) {
        settings.units = system
        _units.value = system
    }

    fun toggleDashboardPid(id: Int) {
        val current = _dashboardPids.value
        val next = if (id in current) current - id else current + id
        _dashboardPids.value = next
        settings.dashboardPids = next
    }

    fun startRecording() {
        synchronized(recorderLock) { recorder.clear() }
        recording = true
        _trip.value = TripState(recording = true, stats = emptyStats())
    }

    fun stopRecording() {
        recording = false
        _trip.value = _trip.value.copy(recording = false, stats = currentStats())
    }

    fun clearRecording() {
        recording = false
        synchronized(recorderLock) { recorder.clear() }
        _trip.value = TripState(recording = false, stats = emptyStats())
    }

    /** Выгрузка CSV - в отдельной корутине: файл пишется не в рабочем цикле опроса. */
    fun exportTrip(onDone: (ExportResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val csv = synchronized(recorderLock) { recorder.toCsv() }
            val result = TripExport.save(getApplication<Application>(), csv)
            withContext(Dispatchers.Main) {
                _trip.value = _trip.value.copy(exportedUri = result.uri)
                onDone(result)
            }
        }
    }

    // --- рабочий цикл -------------------------------------------------------

    private suspend fun runSession(target: ConnectionTarget) {
        val channel = try {
            openChannel(target)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _connection.value = ConnectionState.Failed(error.message ?: error.javaClass.simpleName)
            return
        }
        val session = ObdSession(ElmAdapter(channel))
        try {
            when (val outcome = session.connect { stage -> _connection.value = ConnectionState.Connecting(stage) }) {
                is ConnectOutcome.Failed -> {
                    _connection.value = ConnectionState.Failed(outcome.message)
                    return
                }
                is ConnectOutcome.Connected -> {
                    _supportedPids.value = Pids.ALL.filter { it.id in outcome.supportedPids }
                    ensureDashboardSelection()
                    _connection.value = ConnectionState.Connected(
                        target = target,
                        adapterId = outcome.adapterId,
                        protocol = outcome.protocol,
                        pidCount = outcome.supportedPids.size
                    )
                }
            }
            loadVehicle(session)
            pollLoop(session)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _connection.value = ConnectionState.Failed(error.message ?: error.javaClass.simpleName)
        } finally {
            // Закрыть сокет надо и при отмене корутины - иначе адаптер останется
            // занятым, и следующее подключение упадёт.
            withContext(NonCancellable) { runCatching { session.close() } }
        }
    }

    private fun openChannel(target: ConnectionTarget): ObdChannel = when (target) {
        is ConnectionTarget.Demo -> DemoObdChannel()
        is ConnectionTarget.Wifi -> WifiObdChannel(target.host, target.port).also { it.open() }
        is ConnectionTarget.Bluetooth -> {
            val device = BluetoothDevices.find(getApplication<Application>(), target.address)
                ?: throw IllegalStateException("Адаптер ${target.name} не найден среди сопряжённых")
            BluetoothObdChannel(device).also { it.open() }
        }
    }

    private suspend fun pollLoop(session: ObdSession) {
        var cycle = 0
        var emptyCycles = 0
        while (currentCoroutineContext().isActive) {
            val task = tasks.tryReceive().getOrNull()
            if (task != null) {
                handle(task, session)
                continue
            }
            val pids = pollList(session)
            val values = if (pids.isEmpty()) emptyMap() else session.readAll(pids)
            if (values.isEmpty() && pids.isNotEmpty()) {
                // Несколько пустых циклов подряд - адаптер выдернули или машину
                // заглушили: честнее показать обрыв связи, чем замершие цифры.
                if (++emptyCycles >= LOST_CONNECTION_CYCLES) {
                    _connection.value = ConnectionState.Failed(
                        getApplication<Application>().getString(R.string.connect_lost)
                    )
                    return
                }
            } else {
                emptyCycles = 0
            }
            // Статус лампы Check Engine меняется редко - спрашиваем его не каждый
            // цикл, чтобы не отнимать время у приборов.
            val monitor = if (cycle % MONITOR_EVERY == 0) session.readMonitorStatus() else _live.value.monitor
            publish(values, monitor)
            cycle++
            delay(_pollInterval.value.toLong())
        }
    }

    private fun publish(values: Map<Int, Double>, monitor: MonitorStatus?) {
        val now = System.currentTimeMillis()
        val speed = values[Pids.SPEED.id]
        val litresPerHour = values[Pids.FUEL_RATE.id]
            ?: values[Pids.MAF.id]?.let { FuelEconomy.litresPerHourFromMaf(it, dieselFuel) }
        val litresPer100 = if (litresPerHour != null && speed != null) {
            FuelEconomy.litresPer100Km(litresPerHour, speed)
        } else {
            null
        }
        _live.value = LiveSnapshot(values, litresPerHour, litresPer100, monitor, now)
        if (recording && values.isNotEmpty()) {
            synchronized(recorderLock) { recorder.add(now, values) }
            _trip.value = _trip.value.copy(stats = currentStats())
        }
    }

    /**
     * Что спрашивать в одном цикле: выбранное пользователем плюс то, без чего не
     * посчитать расход и пробег (скорость и расход воздуха), даже если их не
     * показывают на экране.
     */
    private fun pollList(session: ObdSession): List<ObdPid> {
        val ids = LinkedHashSet(_dashboardPids.value)
        ids.add(Pids.SPEED.id)
        if (session.supports(Pids.FUEL_RATE.id)) ids.add(Pids.FUEL_RATE.id) else ids.add(Pids.MAF.id)
        return ids.mapNotNull { Pids.byId[it] }.filter { session.supports(it.id) && it.id !in Pids.STATIC }
    }

    private fun handle(task: ObdTask, session: ObdSession) {
        when (task) {
            ObdTask.ReadDtcs -> {
                val stored = session.readDtcs(DtcSource.STORED)
                val pending = session.readDtcs(DtcSource.PENDING)
                val permanent = session.readDtcs(DtcSource.PERMANENT)
                val monitor = session.readMonitorStatus()
                val freeze = if (stored.isNotEmpty()) {
                    session.readFreezeFrame(FREEZE_FRAME_PIDS.mapNotNull { Pids.byId[it] }
                        .filter { session.supports(it.id) })
                } else {
                    emptyMap()
                }
                _dtc.value = DtcState(
                    reading = false,
                    loaded = true,
                    stored = stored,
                    pending = pending,
                    permanent = permanent,
                    monitor = monitor,
                    freezeFrame = freeze
                )
            }

            ObdTask.ClearDtcs -> {
                val ok = session.clearDtcs()
                val stored = session.readDtcs(DtcSource.STORED)
                val pending = session.readDtcs(DtcSource.PENDING)
                val permanent = session.readDtcs(DtcSource.PERMANENT)
                _dtc.value = DtcState(
                    reading = false,
                    loaded = true,
                    stored = stored,
                    pending = pending,
                    permanent = permanent,
                    monitor = session.readMonitorStatus(),
                    freezeFrame = emptyMap(),
                    message = getApplication<Application>().getString(
                        if (ok) R.string.dtc_cleared else R.string.dtc_clear_failed
                    )
                )
            }

            ObdTask.RefreshVehicle -> loadVehicle(session)

            is ObdTask.Terminal -> {
                val reply: ElmReply = session.raw(task.command)
                val answer = reply.error?.text ?: reply.lines.joinToString(" / ").ifEmpty { "—" }
                _terminal.value = (_terminal.value + "> ${task.command.uppercase()}" + answer).takeLast(TERMINAL_LINES)
            }
        }
    }

    private fun loadVehicle(session: ObdSession) {
        val vin = session.readVin()
        val fuelType = session.read(Pids.FUEL_TYPE)?.toInt()
        dieselFuel = fuelType != null && FuelType.isDiesel(fuelType)
        val counters = session.readAll(
            COUNTER_PIDS.mapNotNull { Pids.byId[it] }.filter { session.supports(it.id) }
        )
        _vehicle.value = VehicleState(
            loading = false,
            loaded = true,
            vin = vin,
            vinInfo = vin?.let { VinDecoder.decode(it) },
            ecuName = session.readEcuName(),
            calibrationId = session.readCalibrationId(),
            adapterVoltage = session.adapterVoltage(),
            obdStandard = session.read(Pids.OBD_STANDARD)?.toInt(),
            fuelType = fuelType,
            counters = counters
        )
    }

    /** Если сохранённый набор параметров машине не подходит - берём пересечение. */
    private fun ensureDashboardSelection() {
        val supported = _supportedPids.value.map { it.id }.toSet()
        val filtered = _dashboardPids.value.filter { it in supported }
        val result = filtered.ifEmpty { Pids.DEFAULT_DASHBOARD.filter { it in supported } }
        _dashboardPids.value = result
    }

    private fun currentStats(): TripStats = synchronized(recorderLock) { recorder.stats(dieselFuel) }

    private fun emptyStats(): TripStats = TripRecorder().stats()

    override fun onCleared() {
        worker?.cancel()
        super.onCleared()
    }

    private companion object {
        /** Сколько пустых циклов подряд считать обрывом связи. */
        const val LOST_CONNECTION_CYCLES = 5

        /** Раз во столько циклов перечитывается статус лампы Check Engine. */
        const val MONITOR_EVERY = 20

        const val TERMINAL_LINES = 40

        /** Что показывать в стоп-кадре - то же, что обычно смотрят по ошибке. */
        val FREEZE_FRAME_PIDS = listOf(
            Pids.RPM.id, Pids.SPEED.id, Pids.ENGINE_LOAD.id, Pids.COOLANT_TEMP.id,
            Pids.THROTTLE.id, Pids.INTAKE_MAP.id, Pids.SHORT_FUEL_TRIM_1.id, Pids.LONG_FUEL_TRIM_1.id
        )

        /** Счётчики "после сброса ошибок" и наработки с горящей лампой. */
        val COUNTER_PIDS = listOf(
            Pids.DISTANCE_SINCE_CLEAR.id, Pids.TIME_SINCE_CLEAR.id, Pids.WARMUPS_SINCE_CLEAR.id,
            Pids.DISTANCE_WITH_MIL.id, Pids.TIME_WITH_MIL.id, Pids.RUN_TIME.id
        )
    }
}
