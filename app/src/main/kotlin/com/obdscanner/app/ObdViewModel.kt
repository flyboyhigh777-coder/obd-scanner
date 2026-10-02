package com.obdscanner.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obdscanner.app.data.AppSettings
import com.obdscanner.app.data.ExportResult
import com.obdscanner.app.data.SaeDtcCatalogAsset
import com.obdscanner.app.data.TripExport
import com.obdscanner.app.transport.BleObdChannel
import com.obdscanner.app.transport.BluetoothObdChannel
import com.obdscanner.app.transport.BluetoothScanner
import com.obdscanner.app.transport.ConnectionTarget
import com.obdscanner.app.transport.DiscoveredDevice
import com.obdscanner.app.transport.DemoObdChannel
import com.obdscanner.app.transport.WifiObdChannel
import com.obdscanner.core.dtc.Dtc
import com.obdscanner.core.dtc.DtcCatalog
import com.obdscanner.core.dtc.DtcSource
import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.elm.ObdProtocol
import com.obdscanner.core.obd.CustomPid
import com.obdscanner.core.obd.FuelEconomy
import com.obdscanner.core.obd.FuelType
import com.obdscanner.core.obd.MonitorStatus
import com.obdscanner.core.obd.ObdPid
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.obd.UnitSystem
import com.obdscanner.core.obd.VinDecoder
import com.obdscanner.core.obd.VinInfo
import com.obdscanner.core.perf.AccelerationRun
import com.obdscanner.core.perf.BrakingRun
import com.obdscanner.core.perf.PerformanceMeter
import com.obdscanner.core.session.ConnectOutcome
import com.obdscanner.core.session.ConnectStage
import com.obdscanner.core.session.ElmAdapter
import com.obdscanner.core.session.ObdChannel
import com.obdscanner.core.session.ObdSession
import com.obdscanner.core.trip.TripRecorder
import com.obdscanner.core.trip.TripStats
import com.obdscanner.core.vehicle.GearEstimator
import com.obdscanner.core.vehicle.TyreSize
import com.obdscanner.core.vehicle.VehicleProfile
import com.obdscanner.core.vehicle.VehicleProfiles
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
    data class Connecting(val stage: ConnectStage, val attempt: Int = 0) : ConnectionState
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
    /** Значения пользовательских PID по имени параметра. */
    val custom: Map<String, Double> = emptyMap(),
    val litresPerHour: Double? = null,
    val litresPer100Km: Double? = null,
    /** Запас хода, км - считается из объёма бака в профиле и среднего расхода. */
    val rangeKm: Double? = null,
    /** Включённая передача, если её удалось распознать. */
    val gear: Int? = null,
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

/** Замеры разгона и торможения. */
data class PerformanceState(
    val measuring: Boolean = false,
    val currentG: Double = 0.0,
    val currentPowerHp: Double? = null,
    val runs: List<AccelerationRun> = emptyList(),
    val brakings: List<BrakingRun> = emptyList(),
    val learnedGears: Int = 0,
    val gearRatios: List<Double> = emptyList()
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
 *
 * Здесь же живёт то, что считается из потока данных: замеры разгона, оценка
 * мощности, распознавание передачи и запас хода. Сама арифметика - в :core и
 * покрыта тестами; тут только подача данных и состояние для экранов.
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

    private val _performance = MutableStateFlow(PerformanceState())
    val performance: StateFlow<PerformanceState> = _performance.asStateFlow()

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

    private val _profile = MutableStateFlow(settings.vehicleProfile())
    val profile: StateFlow<VehicleProfile> = _profile.asStateFlow()

    private val _customPids = MutableStateFlow(settings.customPids)
    val customPids: StateFlow<List<CustomPid>> = _customPids.asStateFlow()

    private val _gaugeStyle = MutableStateFlow(settings.gaugeStyle)
    val gaugeStyle: StateFlow<String> = _gaugeStyle.asStateFlow()

    private val _autoReconnect = MutableStateFlow(settings.autoReconnect)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    // --- поиск адаптеров ----------------------------------------------------

    private val _pairedDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val pairedDevices: StateFlow<List<DiscoveredDevice>> = _pairedDevices.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discovered: StateFlow<List<DiscoveredDevice>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var bleScan: BluetoothScanner.BleScan? = null
    private var scanTimeout: Job? = null

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

    private var meter = PerformanceMeter(profile = _profile.value)
    private var gears = GearEstimator()

    init {
        // Таблица кодов блока управления зависит от выбранной машины.
        DtcCatalog.manufacturerFamily = _profile.value.manufacturerDtcFamily
        dieselFuel = FuelType.isDiesel(_profile.value.fuelTypeCode)
        // Справочник на 9.5 тысячи кодов читается с диска - только не на главном потоке.
        viewModelScope.launch(Dispatchers.IO) { SaeDtcCatalogAsset.load(getApplication()) }
    }

    /** Список сопряжённых устройств: обновляется при входе на экран связи. */
    fun refreshPairedDevices() {
        _pairedDevices.value = BluetoothScanner.pairedDevices(getApplication<Application>())
    }

    /**
     * Поиск BLE-адаптеров. Сканирование - дорогая по питанию операция, поэтому
     * оно само выключается через [SCAN_SECONDS] секунд: забытый включённым
     * сканер сажает батарею быстрее, чем сам опрос машины.
     */
    fun startBleScan() {
        if (_scanning.value) return
        _discovered.value = emptyList()
        val scan = BluetoothScanner.startBleScan(getApplication<Application>()) { device ->
            // Колбэк приходит не из главного потока, поэтому состояние только
            // через StateFlow: он потокобезопасен, в отличие от mutableStateOf.
            val known = _discovered.value
            _discovered.value = if (known.any { it.address == device.address }) {
                known.map { if (it.address == device.address) device else it }
            } else {
                known + device
            }
        } ?: return
        bleScan = scan
        _scanning.value = true
        scanTimeout = viewModelScope.launch {
            delay(SCAN_SECONDS * 1000L)
            stopBleScan()
        }
    }

    fun stopBleScan() {
        scanTimeout?.cancel()
        scanTimeout = null
        bleScan?.stop()
        bleScan = null
        _scanning.value = false
    }

    // --- управление из интерфейса ------------------------------------------

    fun connect(target: ConnectionTarget) {
        worker?.cancel()
        _connection.value = ConnectionState.Connecting(ConnectStage.RESET)
        _live.value = LiveSnapshot()
        _dtc.value = DtcState()
        _vehicle.value = VehicleState()
        _terminal.value = emptyList()
        when (target) {
            is ConnectionTarget.Bluetooth -> settings.lastBluetoothAddress = target.address
            is ConnectionTarget.BluetoothLe -> settings.lastBluetoothAddress = target.address
            else -> Unit
        }
        worker = viewModelScope.launch(Dispatchers.IO) { runWithReconnect(target) }
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

    fun setGaugeStyle(style: String) {
        settings.gaugeStyle = style
        _gaugeStyle.value = style
    }

    fun setAutoReconnect(enabled: Boolean) {
        settings.autoReconnect = enabled
        _autoReconnect.value = enabled
    }

    fun toggleDashboardPid(id: Int) {
        val current = _dashboardPids.value
        val next = if (id in current) current - id else current + id
        _dashboardPids.value = next
        settings.dashboardPids = next
    }

    // --- профиль машины ----------------------------------------------------

    fun selectProfile(id: String) {
        settings.profileId = id
        settings.resetVehicleProfile()
        applyProfile(settings.vehicleProfile())
    }

    fun updateProfile(
        tankLitres: Double? = null,
        curbMassKg: Double? = null,
        payloadKg: Double? = null,
        rpmRedline: Double? = null,
        tyreText: String? = null
    ) {
        settings.updateVehicleProfile(tankLitres, curbMassKg, payloadKg, rpmRedline, tyreText)
        applyProfile(settings.vehicleProfile())
    }

    private fun applyProfile(updated: VehicleProfile) {
        _profile.value = updated
        DtcCatalog.manufacturerFamily = updated.manufacturerDtcFamily
        dieselFuel = FuelType.isDiesel(updated.fuelTypeCode)
        // Масса и аэродинамика входят в оценку мощности: старые замеры к новому
        // профилю отношения не имеют, поэтому замеры начинаются заново.
        meter = PerformanceMeter(profile = updated)
        _performance.value = PerformanceState()
    }

    /** Проверка размера шины для экрана: сразу видно, понят ли ввод. */
    fun parseTyre(text: String): TyreSize? = TyreSize.parse(text)

    val availableProfiles: List<VehicleProfile> get() = VehicleProfiles.ALL

    // --- свои параметры ----------------------------------------------------

    fun addCustomPid(pid: CustomPid) {
        val next = (_customPids.value.filter { it.name != pid.name } + pid).sortedBy { it.name }
        _customPids.value = next
        settings.customPids = next
    }

    fun removeCustomPid(name: String) {
        val next = _customPids.value.filter { it.name != name }
        _customPids.value = next
        settings.customPids = next
    }

    // --- замеры ------------------------------------------------------------

    fun resetPerformance() {
        meter.reset()
        gears.reset()
        _performance.value = PerformanceState()
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

    /**
     * Подключение с автоматическим переподключением.
     *
     * Повтор делается только после УДАЧНОГО соединения: если адаптер однажды
     * ответил, значит он существует, и обрыв - это чаще всего выключенное
     * зажигание или уехавший из машины телефон. А вот ломиться заново на адрес,
     * который не ответил ни разу, бессмысленно - человек скорее выбрал не то
     * устройство, и ему надо об этом сказать.
     */
    private suspend fun runWithReconnect(target: ConnectionTarget) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val established = runSession(target, attempt)
            if (!established || !_autoReconnect.value || attempt >= MAX_RECONNECT_ATTEMPTS) return
            attempt++
            _connection.value = ConnectionState.Connecting(ConnectStage.RESET, attempt)
            delay(RECONNECT_DELAY_MILLIS * attempt)
        }
    }

    /** @return true, если соединение успело установиться (значит, есть смысл повторять). */
    private suspend fun runSession(target: ConnectionTarget, attempt: Int): Boolean {
        val channel = try {
            openChannel(target)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _connection.value = ConnectionState.Failed(error.message ?: error.javaClass.simpleName)
            return false
        }
        val session = ObdSession(ElmAdapter(channel))
        var established = false
        try {
            when (val outcome = session.connect { stage ->
                _connection.value = ConnectionState.Connecting(stage, attempt)
            }) {
                is ConnectOutcome.Failed -> {
                    _connection.value = ConnectionState.Failed(outcome.message)
                    return false
                }

                is ConnectOutcome.Connected -> {
                    established = true
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
            // Закрыть канал надо и при отмене корутины - иначе адаптер останется
            // занятым, и следующее подключение упадёт.
            withContext(NonCancellable) { runCatching { session.close() } }
        }
        return established
    }

    private fun openChannel(target: ConnectionTarget): ObdChannel = when (target) {
        is ConnectionTarget.Demo -> DemoObdChannel()
        is ConnectionTarget.Wifi -> WifiObdChannel(target.host, target.port).also { it.open() }
        is ConnectionTarget.Bluetooth -> {
            val device = BluetoothScanner.findDevice(getApplication<Application>(), target.address)
                ?: throw IllegalStateException("Адаптер ${target.name} не найден")
            BluetoothObdChannel(device).also { it.open() }
        }

        is ConnectionTarget.BluetoothLe -> {
            val device = BluetoothScanner.findDevice(getApplication<Application>(), target.address)
                ?: throw IllegalStateException("Адаптер ${target.name} не найден")
            BleObdChannel(getApplication<Application>(), device).also { it.open() }
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
            val custom = readCustomPids(session)
            // Статус лампы Check Engine меняется редко - спрашиваем его не каждый
            // цикл, чтобы не отнимать время у приборов.
            val monitor = if (cycle % MONITOR_EVERY == 0) session.readMonitorStatus() else _live.value.monitor
            publish(values, custom, monitor)
            cycle++
            delay(_pollInterval.value.toLong())
        }
    }

    private fun readCustomPids(session: ObdSession): Map<String, Double> {
        val list = _customPids.value
        if (list.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (pid in list) {
            session.readCustom(pid)?.let { out[pid.name] = it }
        }
        return out
    }

    private fun publish(values: Map<Int, Double>, custom: Map<String, Double>, monitor: MonitorStatus?) {
        val now = System.currentTimeMillis()
        val vehicle = _profile.value
        // Скорость с шины считается для заводской шины: если стоят другие,
        // настоящая скорость отличается, и все расчёты должны идти по ней.
        val rawSpeed = values[Pids.SPEED.id]
        val speed = rawSpeed?.times(vehicle.speedCorrection)
        val litresPerHour = values[Pids.FUEL_RATE.id]
            ?: values[Pids.MAF.id]?.let { FuelEconomy.litresPerHourFromMaf(it, dieselFuel) }
        val litresPer100 = if (litresPerHour != null && speed != null) {
            FuelEconomy.litresPer100Km(litresPerHour, speed)
        } else {
            null
        }
        val range = litresPer100?.let { consumption ->
            values[Pids.FUEL_LEVEL.id]?.let { level ->
                FuelEconomy.rangeKm(vehicle.tankLitres, level, consumption)
            }
        }
        val rpm = values[Pids.RPM.id]
        var gear: Int? = null
        if (speed != null) {
            if (rpm != null) gear = gears.add(rpm, speed)
            val finished = meter.add(now, speed, rpm)
            if (finished != null || meter.measuring != _performance.value.measuring) {
                _performance.value = PerformanceState(
                    measuring = meter.measuring,
                    currentG = meter.currentG,
                    currentPowerHp = meter.currentPowerHp,
                    runs = meter.runs.toList(),
                    brakings = meter.brakings.toList(),
                    learnedGears = gears.learnedGears,
                    gearRatios = gears.ratios()
                )
            } else {
                _performance.value = _performance.value.copy(
                    currentG = meter.currentG,
                    currentPowerHp = meter.currentPowerHp,
                    learnedGears = gears.learnedGears
                )
            }
        }
        _live.value = LiveSnapshot(
            values = if (speed != null && rawSpeed != speed) values + (Pids.SPEED.id to speed) else values,
            custom = custom,
            litresPerHour = litresPerHour,
            litresPer100Km = litresPer100,
            rangeKm = range,
            gear = gear,
            monitor = monitor,
            updatedAtMillis = now
        )
        if (recording && values.isNotEmpty()) {
            synchronized(recorderLock) { recorder.add(now, _live.value.values) }
            _trip.value = _trip.value.copy(stats = currentStats())
        }
    }

    /**
     * Что спрашивать в одном цикле: выбранное пользователем плюс то, без чего не
     * посчитать расход, пробег и замеры разгона (скорость, обороты и расход
     * воздуха), даже если их не показывают на экране.
     */
    private fun pollList(session: ObdSession): List<ObdPid> {
        val ids = LinkedHashSet(_dashboardPids.value)
        ids.add(Pids.SPEED.id)
        ids.add(Pids.RPM.id)
        if (session.supports(Pids.FUEL_RATE.id)) ids.add(Pids.FUEL_RATE.id) else ids.add(Pids.MAF.id)
        if (session.supports(Pids.FUEL_LEVEL.id)) ids.add(Pids.FUEL_LEVEL.id)
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
                    session.readFreezeFrame(
                        FREEZE_FRAME_PIDS.mapNotNull { Pids.byId[it] }.filter { session.supports(it.id) }
                    )
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
        // Тип топлива с машины важнее, чем из профиля: он прочитан, а не задан.
        fuelType?.let { dieselFuel = FuelType.isDiesel(it) }
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
        stopBleScan()
        super.onCleared()
    }

    private companion object {
        /** Сколько пустых циклов подряд считать обрывом связи. */
        const val LOST_CONNECTION_CYCLES = 5

        /** Раз во столько циклов перечитывается статус лампы Check Engine. */
        const val MONITOR_EVERY = 20

        const val TERMINAL_LINES = 40

        const val MAX_RECONNECT_ATTEMPTS = 3

        /** Сколько секунд искать BLE-адаптеры, прежде чем выключить сканер. */
        const val SCAN_SECONDS = 20
        const val RECONNECT_DELAY_MILLIS = 2500L

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
