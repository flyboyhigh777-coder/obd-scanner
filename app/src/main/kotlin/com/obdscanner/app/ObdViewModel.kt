package com.obdscanner.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obdscanner.app.data.AppSettings
import com.obdscanner.app.data.DocumentationDtc
import com.obdscanner.app.data.DtcArchiveStore
import com.obdscanner.app.data.DtcReferenceAsset
import com.obdscanner.app.data.ExportResult
import com.obdscanner.app.data.TripExport
import com.obdscanner.app.transport.BleObdChannel
import com.obdscanner.app.transport.BluetoothObdChannel
import com.obdscanner.app.transport.BluetoothScanner
import com.obdscanner.app.transport.ConnectionTarget
import com.obdscanner.app.transport.DiscoveredDevice
import com.obdscanner.app.transport.DemoObdChannel
import com.obdscanner.app.transport.WifiObdChannel
import com.obdscanner.core.dtc.ArchivedModuleDtc
import com.obdscanner.core.dtc.Dtc
import com.obdscanner.core.electrical.Battery
import com.obdscanner.core.electrical.BatteryMonitor
import com.obdscanner.core.electrical.BatteryVerdict
import com.obdscanner.core.electrical.CrankingResult
import com.obdscanner.core.dtc.DtcCatalog
import com.obdscanner.core.dtc.DtcSnapshot
import com.obdscanner.core.dtc.DtcSource
import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.elm.ObdProtocol
import com.obdscanner.core.modules.FoundModule
import com.obdscanner.core.modules.ModuleScan
import com.obdscanner.core.obd.CustomPid
import com.obdscanner.core.obd.FuelEconomy
import com.obdscanner.core.obd.FuelType
import com.obdscanner.core.obd.MonitorStatus
import com.obdscanner.core.obd.ObdPid
import com.obdscanner.core.obd.PidScan
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.obd.ScanChange
import com.obdscanner.core.obd.ScanHit
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
    val updatedAtMillis: Long = 0,
    /**
     * Полный цикл обновления, мс: обмен с адаптером плюс пауза из настройки.
     * Именно по нему считается частота на экране. Мерить один обмен нельзя -
     * пауза для стрелок такое же ожидание, и число выходило вдвое больше
     * настоящего.
     */
    val cycleMillis: Long = 0,
    /**
     * Сколько из цикла занял сам обмен с адаптером, мс. Показывается рядом с
     * ползунком периода: без этого числа непонятно, упирается частота в
     * настройку или уже в адаптер.
     */
    val workMillis: Long = 0,
    /**
     * Полный круг, в котором спрашивается вообще всё, включая температуру,
     * уровень топлива и напряжение, мс. Он длиннее быстрого, и выдавать
     * быструю частоту за общую нельзя.
     */
    val slowMillis: Long = 0,
    /** Удалось ли спрашивать параметры пакетом - от этого зависит скорость. */
    val batched: Boolean = false,
    /**
     * Расход не измерен, а посчитан из давления во впуске - на машинах без
     * расходомера иначе никак. Экран обязан про это сказать: это оценка.
     */
    val consumptionEstimated: Boolean = false,
    /**
     * Каких параметров не хватило, чтобы посчитать расход. Пусто - значит
     * расход посчитан.
     */
    val consumptionNeeds: List<String> = emptyList(),
    /** Параметры, которые машина не отдаёт: показываем причину, а не прочерк. */
    val unanswered: Set<Int> = emptySet(),
) {
    val hasData: Boolean get() = values.isNotEmpty()

    /** Обновлений в секунду - так понятнее, чем миллисекунды. */
    val hz: Double? get() = if (cycleMillis > 0) 1000.0 / cycleMillis else null
}

/** Ошибки: три режима чтения, стоп-кадр и бортовые тесты. */
/**
 * Бортовая сеть.
 *
 * Тока здесь нет и быть не может: в OBD-II нет ни одного параметра с током, а
 * косвенно его не вытащить - разрешение ATRV (0.1 В) на два порядка грубее,
 * чем след, который оставляет на напряжении даже большая утечка. Подробный
 * расчёт - в :core, Battery.kt. Поэтому здесь только то, что напряжение
 * действительно показывает: заряд, пуск и работа генератора.
 */
data class BatteryState(
    val volts: Double? = null,
    /** true - значение от адаптера (AT RV), false - из ЭБУ (PID 42). */
    val fromAdapter: Boolean = true,
    val verdict: BatteryVerdict = BatteryVerdict.UNKNOWN,
    val chargePercent: Int? = null,
    val minVolts: Double? = null,
    val maxVolts: Double? = null,
    val cranking: CrankingResult? = null,
    val crankTestActive: Boolean = false,
    /** Последние замеры для графика, в порядке поступления. */
    val history: List<Double> = emptyList(),
)

/**
 * Поиск заводских параметров перебором.
 *
 * [snapshot] - сохранённый снимок значений, [changes] - что изменилось между
 * ним и повторным чтением. Это и есть способ найти параметр, которого нет ни в
 * одной документации: снять снимок, включить фары или обогрев стекла, сравнить.
 */
/**
 * Поиск блоков вне OBD-II: ABS, подушки, АКПП.
 *
 * [possible] = false означает, что на этом протоколе перебор мы не умеем, и
 * кнопку показывать незачем.
 */
data class ModuleScanState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val found: List<FoundModule> = emptyList(),
    val loaded: Boolean = false,
    val possible: Boolean = true,
)

data class PidScanState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val hits: List<ScanHit> = emptyList(),
    val snapshotSize: Int = 0,
    val changes: List<ScanChange> = emptyList(),
    val message: String? = null,
)

data class DtcState(
    val reading: Boolean = false,
    val loaded: Boolean = false,
    val stored: List<Dtc> = emptyList(),
    val pending: List<Dtc> = emptyList(),
    val permanent: List<Dtc> = emptyList(),
    /** Блок не ответил на запрос кодов - это не то же самое, что "кодов нет". */
    val failed: Boolean = false,
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
    data object CrankTest : ObdTask
    data class ScanPids(val from: Int, val to: Int) : ObdTask
    data object ScanModules : ObdTask
    data object SnapshotPids : ObdTask
    data object ComparePids : ObdTask
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

    private val _battery = MutableStateFlow(BatteryState())
    val battery: StateFlow<BatteryState> = _battery.asStateFlow()

    private val _pidScan = MutableStateFlow(PidScanState())
    val pidScan: StateFlow<PidScanState> = _pidScan.asStateFlow()

    private val _modules = MutableStateFlow(ModuleScanState())
    val modules: StateFlow<ModuleScanState> = _modules.asStateFlow()

    /**
     * Прочитанные ошибки, которые остаются после отключения.
     *
     * Коды читают в гараже, а разбираются с ними дома: пока список жил только
     * в памяти, он пропадал ровно тогда, когда становился нужен.
     */
    private val _archive = MutableStateFlow(DtcArchiveStore.load(application))
    val archive: StateFlow<List<DtcSnapshot>> = _archive.asStateFlow()

    /**
     * Меняется при каждой правке своих расшифровок. Нужен только для того,
     * чтобы список кодов перерисовался: сами расшифровки живут в справочнике
     * :core, и Compose не может следить за ними напрямую.
     */
    private val _dtcTableRevision = MutableStateFlow(0)
    val dtcTableRevision: StateFlow<Int> = _dtcTableRevision.asStateFlow()

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

    private val _textScale = MutableStateFlow(settings.textScale)
    val textScale: StateFlow<Float> = _textScale.asStateFlow()

    private val _volumetricEfficiency = MutableStateFlow(settings.volumetricEfficiencyPercent)
    val volumetricEfficiency: StateFlow<Float> = _volumetricEfficiency.asStateFlow()

    private val _showGearTile = MutableStateFlow(settings.showGearTile)
    val showGearTile: StateFlow<Boolean> = _showGearTile.asStateFlow()

    private val _showRangeTile = MutableStateFlow(settings.showRangeTile)
    val showRangeTile: StateFlow<Boolean> = _showRangeTile.asStateFlow()

    private val _profile = MutableStateFlow(settings.vehicleProfile())
    val profile: StateFlow<VehicleProfile> = _profile.asStateFlow()

    private val _customPids = MutableStateFlow(settings.customPids)
    val customPids: StateFlow<List<CustomPid>> = _customPids.asStateFlow()

    private val _gaugeStyle = MutableStateFlow(settings.gaugeStyle)
    val gaugeStyle: StateFlow<String> = _gaugeStyle.asStateFlow()

    private val _autoReconnect = MutableStateFlow(settings.autoReconnect)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _protocol = MutableStateFlow(settings.protocol)
    val protocol: StateFlow<ObdProtocol> = _protocol.asStateFlow()

    /** Привязался ли сокет Wi-Fi к сети адаптера - показывается подсказкой. */
    private val _boundToWifi = MutableStateFlow<Boolean?>(null)
    val boundToWifi: StateFlow<Boolean?> = _boundToWifi.asStateFlow()

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

    private val batteryMonitor = BatteryMonitor()

    /**
     * Поправка к показаниям адаптера. Клоны ELM327 врут по напряжению на
     * десятые доли вольта, а разница между "заряжена" и "подсажена" - как раз
     * десятые. Человек меряет мультиметром и вводит разницу один раз.
     */
    @Volatile
    private var voltageOffset = 0.0

    @Volatile
    private var crankTestRunning = false

    @Volatile
    private var pidScanStop = false
    private var moduleScanStop = false

    /**
     * Время начала подключения - оно же метка записи в архиве. Одно
     * подключение даёт одну запись: коды двигателя и обход блоков делаются по
     * очереди, и разносить их по разным строкам архива значит заставлять
     * человека складывать их обратно глазами.
     */
    private var sessionStartedAt = 0L

    /** Снимок значений для сравнения "до и после". */
    private var pidSnapshot: List<ScanHit> = emptyList()
    private val recorderLock = Any()

    @Volatile private var recording = false
    @Volatile private var dieselFuel = false

    private var meter = PerformanceMeter(profile = _profile.value)
    private var gears = GearEstimator()

    init {
        voltageOffset = settings.voltageOffset.toDouble()
        // Таблица кодов блока управления зависит от выбранной машины.
        DtcCatalog.manufacturerFamily = _profile.value.manufacturerDtcFamily
        dieselFuel = FuelType.isDiesel(_profile.value.fuelTypeCode)
        // Справочники читаются с диска - только не на главном потоке.
        viewModelScope.launch(Dispatchers.IO) {
            DtcReferenceAsset.load(getApplication())
            DocumentationDtc.load(getApplication())
        }
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
        _boundToWifi.value = null
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

    /**
     * Своя расшифровка кода: то, что человек нашёл в руководстве по ремонту или
     * у диагноста. Пустой текст убирает её.
     *
     * Счётчик [dtcTableRevision] существует потому, что расшифровки лежат в
     * справочнике :core, а не в состоянии экрана: Compose о таком изменении сам
     * не узнает, и без счётчика список кодов остался бы со старым текстом.
     */
    fun setDtcDescription(code: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            DocumentationDtc.put(getApplication(), code, text)
            _dtcTableRevision.value++
        }
    }

    /** Импорт таблицы расшифровок из файла, выбранного на телефоне. */
    fun importDtcTable(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val text = runCatching {
                app.contentResolver.openInputStream(uri)?.use { stream ->
                    // Таблицы из Excel и с русских сайтов часто в cp1251;
                    // читаем как UTF-8, а при явном сбое пробуем windows-1251.
                    val bytes = stream.readBytes()
                    val utf8 = bytes.toString(Charsets.UTF_8)
                    if (utf8.contains('\uFFFD')) {
                        bytes.toString(charset("windows-1251"))
                    } else {
                        utf8
                    }
                }
            }.getOrNull()
            val added = if (text == null) 0 else DocumentationDtc.import(app, text)
            _dtcTableRevision.value++
            _dtc.value = _dtc.value.copy(
                message = if (added > 0) {
                    app.getString(R.string.dtc_import_done, added)
                } else {
                    app.getString(R.string.dtc_import_failed)
                }
            )
        }
    }

    /** Убирает все расшифровки, введённые вручную. */
    fun clearDtcDescriptions() {
        viewModelScope.launch(Dispatchers.IO) {
            DocumentationDtc.clearUser(getApplication())
            _dtcTableRevision.value++
        }
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

    /** Плитка передачи: считается из оборотов и скорости, нужна не всем. */
    fun setShowGearTile(show: Boolean) {
        settings.showGearTile = show
        _showGearTile.value = show
    }

    fun setShowRangeTile(show: Boolean) {
        settings.showRangeTile = show
        _showRangeTile.value = show
    }

    /** Коэффициент наполнения, проценты - подгонка расчёта расхода под мотор. */
    fun setVolumetricEfficiency(percent: Float) {
        settings.volumetricEfficiencyPercent = percent
        _volumetricEfficiency.value = settings.volumetricEfficiencyPercent
    }

    /** Размер текста во всём приложении - множитель к системному. */
    fun setTextScale(scale: Float) {
        settings.textScale = scale
        _textScale.value = settings.textScale
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

    fun setProtocol(protocol: ObdProtocol) {
        settings.protocol = protocol
        _protocol.value = protocol
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
        batteryAh: Double? = null,
        tyreText: String? = null
    ) {
        settings.updateVehicleProfile(tankLitres, curbMassKg, payloadKg, rpmRedline, batteryAh, tyreText)
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
        val session = ObdSession(ElmAdapter(channel), preferredProtocol = _protocol.value)
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
                    // Новое подключение - новая запись в архиве.
                    sessionStartedAt = System.currentTimeMillis()
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
        is ConnectionTarget.Wifi -> WifiObdChannel(
            target.host,
            target.port,
            context = getApplication<Application>()
        ).also {
            it.open()
            // Если сокет не удалось привязать к сети адаптера, Android может
            // гонять запросы через мобильную сеть - экран связи об этом скажет.
            _boundToWifi.value = it.boundToWifi
        }
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
        // Последние известные значения. Медленные параметры читаются не каждый
        // круг, и без этой памяти их плитки мигали бы прочерком.
        val latest = LinkedHashMap<Int, Double>()
        var fastMillis = 0L
        var fastWork = 0L
        var slowMillis = 0L
        while (currentCoroutineContext().isActive) {
            val task = tasks.tryReceive().getOrNull()
            if (task != null) {
                handle(task, session)
                continue
            }
            val startedAt = System.currentTimeMillis()
            val all = pollList(session)
            // Главная причина, по которой стрелка отстаёт от педали: в круг
            // набивается полтора десятка параметров, а в одну команду влезает
            // шесть - то есть три обмена с адаптером вместо одного. Обороты и
            // дроссель спрашиваем каждый круг, а температуру и уровень
            // топлива - раз в SLOW_EVERY кругов: за 300 мс они не меняются.
            val fast = all.filter { it.id in Pids.FAST }
            val slowTurn = cycle % SLOW_EVERY == 0 || fast.isEmpty()
            val pids = if (slowTurn) all else fast
            val fresh = if (pids.isEmpty()) emptyMap() else session.readAll(pids)
            latest.putAll(fresh)
            val values: Map<Int, Double> = LinkedHashMap(latest)
            if (fresh.isEmpty() && pids.isNotEmpty()) {
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
            // Напряжение - отдельная команда поверх приборов, и на быстром
            // круге ей делать нечего: оно меняется медленнее температуры.
            if (slowTurn) readBatteryVoltage(session, values[Pids.RPM.id])
            val custom = readCustomPids(session)
            // Статус лампы Check Engine меняется редко - спрашиваем его не каждый
            // цикл, чтобы не отнимать время у приборов.
            val monitor = if (cycle % MONITOR_EVERY == 0) session.readMonitorStatus() else _live.value.monitor
            // Цикл - это обмен плюс пауза: стрелки меняются раз в такой цикл,
            // и частота считается по нему. Пауза берётся в локальную
            // переменную, чтобы показанное число и настоящее ожидание не
            // разъехались, если настройку подвинут прямо сейчас.
            val pause = _pollInterval.value.toLong()
            val work = System.currentTimeMillis() - startedAt
            // Быстрый и полный круги меряем порознь: показать их одним числом
            // значит показать то одно, то другое через раз.
            if (slowTurn) {
                slowMillis = work + pause
            } else {
                fastMillis = work + pause
                fastWork = work
            }
            publish(
                values = values,
                custom = custom,
                monitor = monitor,
                cycleMillis = if (fastMillis > 0) fastMillis else work + pause,
                workMillis = if (fastMillis > 0) fastWork else work,
                slowMillis = slowMillis,
                batched = session.multiPidSupported,
                unanswered = session.unansweredPids,
            )
            cycle++
            delay(pause)
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

    /**
     * Напряжение бортовой сети.
     *
     * Сначала спрашиваем адаптер (`AT RV`): он меряет прямо на разъёме, то
     * есть практически на батарее, и отвечает даже при выключенном зажигании -
     * когда ЭБУ уже молчит. PID 42 идёт запасным: это напряжение питания
     * контроллера, оно чуть ниже из-за проводки, но лучше, чем ничего.
     */
    private fun readBatteryVoltage(session: ObdSession, rpm: Double?) {
        val fromAdapter = session.adapterVoltage()
        val volts = fromAdapter ?: session.read(Pids.CONTROL_MODULE_VOLTAGE)
        if (volts == null) return
        val corrected = volts + voltageOffset
        // Обороты - лучший признак работы мотора, но их может не быть в опросе:
        // набор приборов настраивается, и обороты из него можно убрать. Тогда
        // судим по напряжению: столько держит только генератор. Разница
        // принципиальная - 14 В при работающем моторе это норма, а при
        // заглушенном такого напряжения просто не бывает.
        val running = if (rpm != null) rpm >= Battery.RUNNING_RPM else corrected >= ALTERNATOR_VOLTS
        batteryMonitor.add(System.currentTimeMillis(), corrected, running)
        publishBattery(fromAdapter = fromAdapter != null)
    }

    /**
     * Тест пуска: быстрый опрос только напряжения.
     *
     * Прокрутка стартером длится меньше секунды, и на обычном периоде опроса
     * (вместе со всеми приборами) провал проскакивает между замерами. Здесь
     * ничего, кроме `AT RV`, не спрашивается, поэтому замеры идут раз в
     * ~120 мс и провал виден целиком.
     */
    private suspend fun runCrankTest(session: ObdSession) {
        crankTestRunning = true
        batteryMonitor.reset()
        publishBattery(crankTestActive = true)
        val deadline = System.currentTimeMillis() + CRANK_TEST_MILLIS
        while (crankTestRunning &&
            System.currentTimeMillis() < deadline &&
            currentCoroutineContext().isActive
        ) {
            val volts = session.adapterVoltage()
            if (volts != null) {
                val corrected = volts + voltageOffset
                // Обороты в этом режиме не опрашиваются, поэтому про работу
                // мотора судим по напряжению: генератор поднимает сеть выше
                // любого состояния покоя.
                batteryMonitor.add(System.currentTimeMillis(), corrected, corrected >= ALTERNATOR_VOLTS)
                publishBattery(crankTestActive = true)
                // Пуск состоялся и мотор работает - дальше ждать нечего.
                if (batteryMonitor.cranking != null && corrected >= ALTERNATOR_VOLTS) break
            }
            delay(CRANK_SAMPLE_MILLIS)
        }
        crankTestRunning = false
        publishBattery(crankTestActive = false)
    }

    /**
     * Перебор заводских параметров.
     *
     * Идёт в той же корутине, что и опрос приборов: адаптер последовательный,
     * и перебор всё равно занял бы его целиком. Приборы на это время замирают -
     * это честнее, чем мешать два потока запросов в одном проводе.
     */
    private fun runPidScan(session: ObdSession, from: Int, to: Int) {
        pidScanStop = false
        val range = PidScan.clampRange(from, to)
        _pidScan.value = PidScanState(running = true, total = range.size)
        val hits = session.scanService22(
            range.from,
            range.to,
            shouldStop = { pidScanStop },
            onProgress = { done, total, _ ->
                _pidScan.value = _pidScan.value.copy(done = done, total = total)
            },
        )
        pidSnapshot = emptyList()
        _pidScan.value = _pidScan.value.copy(
            running = false,
            hits = hits,
            snapshotSize = 0,
            changes = emptyList(),
            message = getApplication<Application>().getString(
                if (hits.isEmpty()) R.string.scan_none else R.string.scan_found,
                hits.size,
            ),
        )
    }

    /** Перечитывает найденные параметры и показывает, что изменилось. */
    private fun runPidCompare(session: ObdSession) {
        val before = pidSnapshot
        if (before.isEmpty()) return
        pidScanStop = false
        _pidScan.value = _pidScan.value.copy(running = true, done = 0, total = before.size)
        val after = session.readService22(
            before.map { it.pid },
            shouldStop = { pidScanStop },
        )
        val changes = PidScan.compare(before, after)
        _pidScan.value = _pidScan.value.copy(
            running = false,
            hits = after,
            changes = changes,
            message = getApplication<Application>().getString(
                if (changes.isEmpty()) R.string.scan_no_change else R.string.scan_changed,
                changes.size,
            ),
        )
    }

    /** Запускает перебор диапазона номеров. */
    fun startPidScan(from: Int, to: Int) {
        if (_pidScan.value.running) return
        tasks.trySend(ObdTask.ScanPids(from, to))
    }

    fun stopPidScan() {
        pidScanStop = true
    }

    /**
     * Поиск блоков, которых нет в OBD-II.
     *
     * Нужен ровно для одного: лампа ABS на панели горит, а на вкладке
     * "Ошибки" пусто - потому что режим 03 спрашивает только двигатель.
     */
    fun scanModules() {
        if (_modules.value.running) return
        tasks.trySend(ObdTask.ScanModules)
    }

    fun stopModuleScan() {
        moduleScanStop = true
    }

    private fun runModuleScan(session: ObdSession) {
        if (!session.canScanModules) {
            _modules.value = ModuleScanState(loaded = true, possible = false)
            return
        }
        moduleScanStop = false
        _modules.value = ModuleScanState(
            running = true,
            total = ModuleScan.LAST_ADDRESS - ModuleScan.FIRST_ADDRESS + 1,
        )
        val found = session.scanModules(
            shouldStop = { moduleScanStop },
            onProgress = { done, total, _ ->
                _modules.value = _modules.value.copy(done = done, total = total)
            },
        )
        _modules.value = ModuleScanState(running = false, loaded = true, found = found)
        archiveCurrentReading()
    }

    /**
     * Складывает в архив всё, что прочитано за это подключение.
     *
     * Коды двигателя и обход блоков попадают в одну запись: читают их по
     * очереди, а смотреть потом будут вместе.
     */
    private fun archiveCurrentReading() {
        val dtc = _dtc.value
        val snapshot = DtcSnapshot(
            timeMillis = if (sessionStartedAt > 0) sessionStartedAt else System.currentTimeMillis(),
            vehicle = _profile.value.name,
            vin = _vehicle.value.vin,
            stored = dtc.stored.map { it.code },
            pending = dtc.pending.map { it.code },
            permanent = dtc.permanent.map { it.code },
            modules = _modules.value.found.flatMap { module ->
                module.dtcs.map { ArchivedModuleDtc(module.address, it.full) }
            },
            milOn = dtc.monitor?.milOn ?: false,
        )
        _archive.value = DtcArchiveStore.add(getApplication(), snapshot)
    }

    /** Очистить архив - его смотрят глазами, и мусор в нём мешает. */
    fun clearArchive() {
        _archive.value = DtcArchiveStore.clear(getApplication())
    }

    /** Запоминает текущие значения, чтобы потом сравнить с ними. */
    fun takePidSnapshot() {
        tasks.trySend(ObdTask.SnapshotPids)
    }

    /** Перечитывает параметры и показывает разницу со снимком. */
    fun comparePidSnapshot() {
        if (_pidScan.value.running) return
        tasks.trySend(ObdTask.ComparePids)
    }

    fun clearPidScan() {
        pidSnapshot = emptyList()
        _pidScan.value = PidScanState()
    }

    private fun publishBattery(
        fromAdapter: Boolean = _battery.value.fromAdapter,
        crankTestActive: Boolean = _battery.value.crankTestActive,
    ) {
        _battery.value = BatteryState(
            volts = batteryMonitor.last?.volts,
            fromAdapter = fromAdapter,
            verdict = batteryMonitor.verdict(),
            chargePercent = batteryMonitor.restingChargePercent(),
            minVolts = batteryMonitor.minVolts,
            maxVolts = batteryMonitor.maxVolts,
            cranking = batteryMonitor.cranking,
            crankTestActive = crankTestActive,
            history = batteryMonitor.samples.takeLast(VOLTAGE_HISTORY).map { it.volts },
        )
    }

    /** Запускает тест пуска: нажать, потом поворачивать ключ. */
    fun startCrankTest() {
        if (crankTestRunning) return
        tasks.trySend(ObdTask.CrankTest)
    }

    fun stopCrankTest() {
        crankTestRunning = false
    }

    /** Сбрасывает минимум, максимум и результат пуска. */
    fun resetBattery() {
        batteryMonitor.reset()
        publishBattery(crankTestActive = false)
    }

    /** Поправка к показаниям адаптера, в вольтах. */
    fun updateVoltageOffset(offset: Double) {
        voltageOffset = offset
        settings.voltageOffset = offset.toFloat()
    }

    val voltageCalibration: Double get() = voltageOffset

    private fun publish(
        values: Map<Int, Double>,
        custom: Map<String, Double>,
        monitor: MonitorStatus?,
        cycleMillis: Long = 0,
        workMillis: Long = 0,
        slowMillis: Long = 0,
        batched: Boolean = false,
        unanswered: Set<Int> = emptySet(),
    ) {
        val now = System.currentTimeMillis()
        val vehicle = _profile.value
        // Скорость с шины считается для заводской шины: если стоят другие,
        // настоящая скорость отличается, и все расчёты должны идти по ней.
        val rawSpeed = values[Pids.SPEED.id]
        val speed = rawSpeed?.times(vehicle.speedCorrection)
        // Третий путь - для машин, у которых нет ни расходомера, ни PID
        // расхода. ЗМЗ-409 именно такой: воздух он считает сам по давлению во
        // впуске, а наружу не отдаёт ни того, ни другого.
        // Выбор источника живёт в :core и покрыт тестами: именно он однажды и
        // подвёл - блок заявлял расходомер, на который не отвечал.
        val fuel = FuelEconomy.litresPerHour(
            fuelRate = values[Pids.FUEL_RATE.id],
            maf = values[Pids.MAF.id],
            rpm = values[Pids.RPM.id],
            mapKpa = values[Pids.INTAKE_MAP.id],
            intakeTempC = values[Pids.INTAKE_TEMP.id],
            displacementL = vehicle.displacementL,
            volumetricEfficiency = _volumetricEfficiency.value / 100.0,
            diesel = dieselFuel,
        )
        val estimated = fuel?.estimated == true
        val litresPerHour = fuel?.litresPerHour
        // Прочерк без объяснения - худший вид ответа: непонятно, ждать или
        // чинить. Перечисляем, чего не хватило, чтобы посчитать.
        val consumptionNeeds = if (litresPerHour != null) {
            emptyList()
        } else {
            buildList {
                if (values[Pids.RPM.id] == null) add(Pids.RPM.title)
                if (values[Pids.INTAKE_MAP.id] == null) add(Pids.INTAKE_MAP.title)
            }
        }
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
            updatedAtMillis = now,
            cycleMillis = cycleMillis,
            workMillis = workMillis,
            slowMillis = slowMillis,
            batched = batched,
            consumptionEstimated = estimated,
            consumptionNeeds = consumptionNeeds,
            unanswered = unanswered,
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
        val chosen = _dashboardPids.value.toSet()
        val ids = LinkedHashSet(chosen)
        ids.add(Pids.SPEED.id)
        ids.add(Pids.RPM.id)
        // Чем считать расход. Прямой PID лучше всего, расходомер - следом, а
        // если нет ни того, ни другого (ЗМЗ-409), остаётся давление во впуске.
        //
        // Давление и температуру впуска добавляем во всех случаях, кроме
        // готового расхода: блок может ЗАЯВИТЬ расходомер и при этом на него
        // не отвечать - тогда без запасного пути расход пропадает совсем, а
        // пара лишних номеров едет в том же пакете и почти ничего не стоит.
        if (session.supports(Pids.FUEL_RATE.id)) {
            ids.add(Pids.FUEL_RATE.id)
        } else {
            if (session.supports(Pids.MAF.id)) ids.add(Pids.MAF.id)
            ids.add(Pids.INTAKE_MAP.id)
            ids.add(Pids.INTAKE_TEMP.id)
        }
        if (session.supports(Pids.FUEL_LEVEL.id)) ids.add(Pids.FUEL_LEVEL.id)
        // Выбранное руками спрашиваем, даже если машина про этот параметр не
        // сообщала: блоки бывают скромные и отдают больше, чем заявляют. А вот
        // то, что приложение добавило само, - только из заявленного.
        return ids.mapNotNull { Pids.byId[it] }
            .filter { it.id !in Pids.STATIC && (it.id in chosen || session.supports(it.id)) }
    }

    private suspend fun handle(task: ObdTask, session: ObdSession) {
        when (task) {
            ObdTask.ReadDtcs -> {
                // null - блок не ответил. Выдавать это за чистую машину нельзя:
                // человек поверит и поедет дальше с неисправностью.
                val stored = session.readDtcsOrNull(DtcSource.STORED)
                val pending = session.readDtcsOrNull(DtcSource.PENDING)
                val permanent = session.readDtcsOrNull(DtcSource.PERMANENT)
                val monitor = session.readMonitorStatus()
                val freeze = if (!stored.isNullOrEmpty()) {
                    session.readFreezeFrame(
                        FREEZE_FRAME_PIDS.mapNotNull { Pids.byId[it] }.filter { session.supports(it.id) }
                    )
                } else {
                    emptyMap()
                }
                _dtc.value = DtcState(
                    reading = false,
                    loaded = true,
                    stored = stored.orEmpty(),
                    pending = pending.orEmpty(),
                    permanent = permanent.orEmpty(),
                    failed = stored == null,
                    monitor = monitor,
                    freezeFrame = freeze
                )
                archiveCurrentReading()
            }

            ObdTask.ClearDtcs -> {
                val ok = session.clearDtcs()
                val stored = session.readDtcsOrNull(DtcSource.STORED)
                val pending = session.readDtcsOrNull(DtcSource.PENDING)
                val permanent = session.readDtcsOrNull(DtcSource.PERMANENT)
                _dtc.value = DtcState(
                    reading = false,
                    loaded = true,
                    stored = stored.orEmpty(),
                    pending = pending.orEmpty(),
                    permanent = permanent.orEmpty(),
                    failed = stored == null,
                    monitor = session.readMonitorStatus(),
                    freezeFrame = emptyMap(),
                    message = getApplication<Application>().getString(
                        if (ok) R.string.dtc_cleared else R.string.dtc_clear_failed
                    )
                )
            }

            ObdTask.RefreshVehicle -> loadVehicle(session)

            ObdTask.CrankTest -> runCrankTest(session)

            is ObdTask.ScanPids -> runPidScan(session, task.from, task.to)

            ObdTask.ScanModules -> runModuleScan(session)

            ObdTask.SnapshotPids -> {
                pidSnapshot = _pidScan.value.hits
                _pidScan.value = _pidScan.value.copy(
                    snapshotSize = pidSnapshot.size,
                    changes = emptyList(),
                    message = null,
                )
            }

            ObdTask.ComparePids -> runPidCompare(session)

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

    /**
     * Что показывать на приборах, если выбора ещё нет.
     *
     * Сам выбор не трогаем: раньше здесь отбрасывалось всё, чего машина не
     * заявила, и включённый вручную параметр пропадал при следующем
     * подключении. Заявленный список - не истина в последней инстанции, блок
     * может отдавать больше; решает человек.
     */
    private fun ensureDashboardSelection() {
        if (_dashboardPids.value.isNotEmpty()) return
        val supported = _supportedPids.value.map { it.id }.toSet()
        _dashboardPids.value = Pids.DEFAULT_DASHBOARD.filter { it in supported }
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

        /**
         * Раз во столько циклов перечитывается напряжение. Это отдельная
         * команда, а меняется оно медленно - каждый цикл на него тратиться
         * незачем.
         */
        /**
         * Раз во столько кругов спрашивается всё подряд. Четыре быстрых круга
         * на один полный: обороты обновляются вчетверо чаще, а температура -
         * раз в треть секунды, чего ей с запасом хватает.
         */
        const val SLOW_EVERY = 5

        const val TERMINAL_LINES = 40

        const val MAX_RECONNECT_ATTEMPTS = 3

        /** Сколько секунд искать BLE-адаптеры, прежде чем выключить сканер. */
        const val SCAN_SECONDS = 20

        /** Сколько ждать пуска после нажатия кнопки теста. */
        const val CRANK_TEST_MILLIS = 40_000L

        /** Период опроса напряжения во время теста пуска. */
        const val CRANK_SAMPLE_MILLIS = 120L

        /** Выше этого напряжения сеть держит генератор, а не батарея. */
        const val ALTERNATOR_VOLTS = 13.2

        /** Сколько последних замеров напряжения держать для графика. */
        const val VOLTAGE_HISTORY = 240
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
