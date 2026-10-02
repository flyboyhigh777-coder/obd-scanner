package com.obdscanner.app.data

import android.content.Context
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.obd.UnitSystem

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

    /** Последний адаптер - чтобы не искать его в списке каждый раз. */
    var lastBluetoothAddress: String?
        get() = prefs.getString(KEY_LAST_BT, null)
        set(value) = prefs.edit().putString(KEY_LAST_BT, value).apply()

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
        const val KEY_DASH = "dashboard_pids"

        const val DEFAULT_POLL = 300
        const val MIN_POLL = 100
        const val MAX_POLL = 2000
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
    }
}
