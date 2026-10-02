package com.obdscanner.app.data

import android.content.Context
import com.obdscanner.core.elm.ObdProtocol
import com.obdscanner.core.obd.CustomPid
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.obd.UnitSystem
import com.obdscanner.core.vehicle.TyreSize
import com.obdscanner.core.vehicle.VehicleProfile
import com.obdscanner.core.vehicle.VehicleProfiles

/**
 * Настройки приложения в SharedPreferences. Их мало и они простые (период
 * опроса, единицы, адрес Wi-Fi адаптера, набор параметров на приборке), поэтому
 * DataStore с корутинами и Flow здесь был бы лишней зависимостью и лишним
 * асинхронным слоем на пустом месте.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("obd_scanner", Context.MODE_PRIVATE)

    var pollIntervalMillis: Int
        get() = prefs.getInt(KEY_POLL, DEFAULT_POLL).coerceIn(MIN_POLL, MAX_POLL)
        set(value) = prefs.edit().putInt(KEY_POLL, value.coerceIn(MIN_POLL, MAX_POLL)).apply()

    var units: UnitSystem
        get() = if (prefs.getBoolean(KEY_IMPERIAL, false)) UnitSystem.IMPERIAL else UnitSystem.METRIC
        set(value) = prefs.edit().putBoolean(KEY_IMPERIAL, value == UnitSystem.IMPERIAL).apply()

    var wifiHost: String
        get() = prefs.getString(KEY_WIFI_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_WIFI_HOST, value).apply()

    var wifiPort: Int
        get() = prefs.getInt(KEY_WIFI_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_WIFI_PORT, value).apply()

    /**
     * Чем подключались в прошлый раз: Wi-Fi, классический Bluetooth, BLE или
     * демо. Экран связи открывается сразу на нужном способе - у человека
     * адаптер один и тот же, и выбирать его каждый раз незачем.
     */
    var lastSource: String
        get() = prefs.getString(KEY_LAST_SOURCE, "WIFI") ?: "WIFI"
        set(value) = prefs.edit().putString(KEY_LAST_SOURCE, value).apply()

    /** Последний адаптер - чтобы не искать его в списке каждый раз. */
    var lastBluetoothAddress: String?
        get() = prefs.getString(KEY_LAST_BT, null)
        set(value) = prefs.edit().putString(KEY_LAST_BT, value).apply()

    /** Автоматически переподключаться, если связь с адаптером оборвалась. */
    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    /**
     * Протокол шины, который задаётся адаптеру. Автоопределение работает всегда,
     * но клоны ELM327 перебирают протоколы заново на каждом подключении - если
     * протокол машины известен, несколько секунд экономятся на каждом запуске.
     */
    var protocol: ObdProtocol
        get() = ObdProtocol.entries.firstOrNull { it.name == prefs.getString(KEY_PROTOCOL, null) }
            ?: ObdProtocol.AUTO
        set(value) = prefs.edit().putString(KEY_PROTOCOL, value.name).apply()

    /** Вид приборов: шкала, полоска или просто цифры. */
    var gaugeStyle: String
        get() = prefs.getString(KEY_GAUGE_STYLE, "DIAL") ?: "DIAL"
        set(value) = prefs.edit().putString(KEY_GAUGE_STYLE, value).apply()

    // --- профиль машины: берём готовый и правим под себя ---------------------

    var profileId: String
        get() = prefs.getString(KEY_PROFILE, VehicleProfiles.UAZ_PATRIOT_2016.id)
            ?: VehicleProfiles.UAZ_PATRIOT_2016.id
        set(value) = prefs.edit().putString(KEY_PROFILE, value).apply()

    /**
     * Профиль с поправками пользователя. Хранятся не все поля, а только те, что
     * человек может знать про свою машину и что влияет на расчёты: бак, масса,
     * шины, красная зона. Остальное (аэродинамика, сопротивление качению)
     * остаётся из профиля - его всё равно негде взять точнее.
     */
    fun vehicleProfile(): VehicleProfile {
        val base = VehicleProfiles.byId(profileId)
        return base.copy(
            tankLitres = prefs.getFloat(KEY_TANK, base.tankLitres.toFloat()).toDouble(),
            curbMassKg = prefs.getFloat(KEY_MASS, base.curbMassKg.toFloat()).toDouble(),
            payloadKg = prefs.getFloat(KEY_PAYLOAD, base.payloadKg.toFloat()).toDouble(),
            rpmRedline = prefs.getFloat(KEY_REDLINE, base.rpmRedline.toFloat()).toDouble(),
            tyre = prefs.getString(KEY_TYRE, null)?.let { TyreSize.parse(it) } ?: base.tyre
        )
    }

    fun updateVehicleProfile(
        tankLitres: Double? = null,
        curbMassKg: Double? = null,
        payloadKg: Double? = null,
        rpmRedline: Double? = null,
        tyreText: String? = null
    ) {
        prefs.edit().apply {
            tankLitres?.let { putFloat(KEY_TANK, it.toFloat()) }
            curbMassKg?.let { putFloat(KEY_MASS, it.toFloat()) }
            payloadKg?.let { putFloat(KEY_PAYLOAD, it.toFloat()) }
            rpmRedline?.let { putFloat(KEY_REDLINE, it.toFloat()) }
            tyreText?.let { putString(KEY_TYRE, it) }
        }.apply()
    }

    /** Сброс поправок к значениям профиля - на случай "я тут напутал". */
    fun resetVehicleProfile() {
        prefs.edit()
            .remove(KEY_TANK).remove(KEY_MASS).remove(KEY_PAYLOAD)
            .remove(KEY_REDLINE).remove(KEY_TYRE)
            .apply()
    }

    // --- пользовательские PID ------------------------------------------------

    /**
     * Свои параметры хранятся строками "имя|режим|pid|байты|формула|единица|мин|макс|знаки".
     * Разделитель - вертикальная черта: в формулах и названиях её не бывает, а
     * JSON ради девяти полей тянул бы за собой разбор и его ошибки.
     */
    var customPids: List<CustomPid>
        get() = (prefs.getStringSet(KEY_CUSTOM_PIDS, emptySet()) ?: emptySet())
            .mapNotNull { decodeCustomPid(it) }
            .sortedBy { it.name }
        set(value) = prefs.edit()
            .putStringSet(KEY_CUSTOM_PIDS, value.map { encodeCustomPid(it) }.toSet())
            .apply()

    private fun encodeCustomPid(pid: CustomPid): String = listOf(
        pid.name.replace('|', '/'), pid.mode.toString(), pid.pid.toString(), pid.dataBytes.toString(),
        pid.formulaText.replace('|', '/'), pid.unitLabel.replace('|', '/'),
        pid.min.toString(), pid.max.toString(), pid.decimals.toString()
    ).joinToString("|")

    private fun decodeCustomPid(text: String): CustomPid? {
        val parts = text.split('|')
        if (parts.size < 9) return null
        return CustomPid(
            name = parts[0],
            mode = parts[1].toIntOrNull() ?: return null,
            pid = parts[2].toIntOrNull() ?: return null,
            dataBytes = parts[3].toIntOrNull() ?: return null,
            formulaText = parts[4],
            unitLabel = parts[5],
            min = parts[6].toDoubleOrNull() ?: 0.0,
            max = parts[7].toDoubleOrNull() ?: 100.0,
            decimals = parts[8].toIntOrNull() ?: 1
        )
    }

    /** Какие PID показывать на приборке. Пустой список - значит "набор по умолчанию". */
    var dashboardPids: List<Int>
        get() {
            val raw = prefs.getString(KEY_DASH, null) ?: return Pids.DEFAULT_DASHBOARD
            val ids = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            return ids.ifEmpty { Pids.DEFAULT_DASHBOARD }
        }
        set(value) = prefs.edit().putString(KEY_DASH, value.joinToString(",")).apply()

    private companion object {
        const val KEY_POLL = "poll_interval"
        const val KEY_IMPERIAL = "imperial"
        const val KEY_WIFI_HOST = "wifi_host"
        const val KEY_WIFI_PORT = "wifi_port"
        const val KEY_LAST_BT = "last_bluetooth"
        const val KEY_LAST_SOURCE = "last_source"
        const val KEY_DASH = "dashboard_pids"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_GAUGE_STYLE = "gauge_style"
        const val KEY_PROTOCOL = "obd_protocol"
        const val KEY_PROFILE = "vehicle_profile"
        const val KEY_TANK = "tank_litres"
        const val KEY_MASS = "curb_mass"
        const val KEY_PAYLOAD = "payload"
        const val KEY_REDLINE = "redline"
        const val KEY_TYRE = "tyre"
        const val KEY_CUSTOM_PIDS = "custom_pids"

        const val DEFAULT_POLL = 300
        const val MIN_POLL = 100
        const val MAX_POLL = 2000
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
    }
}
