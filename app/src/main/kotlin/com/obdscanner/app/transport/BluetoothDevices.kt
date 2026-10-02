package com.obdscanner.app.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Сопряжённый адаптер в списке на экране подключения. */
data class PairedDevice(val name: String, val address: String)

/**
 * Доступ к сопряжённым Bluetooth-устройствам.
 *
 * Поиск новых устройств сознательно не делается: на Android он требует
 * BLUETOOTH_SCAN и доступа к местоположению, а сопряжение адаптера - разовая
 * операция, которую система и так умеет лучше. Приложению достаточно списка уже
 * сопряжённых устройств.
 *
 * Все обращения к адаптеру завёрнуты в try/catch не из перестраховки: на
 * Android 12+ чтение списка без BLUETOOTH_CONNECT бросает SecurityException, а
 * на устройствах без Bluetooth системный сервис может вовсе отсутствовать.
 */
object BluetoothDevices {

    /** Нужно ли спрашивать разрешение в рантайме (Android 12 и новее). */
    val needsRuntimePermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val permission: String = Manifest.permission.BLUETOOTH_CONNECT

    fun hasPermission(context: Context): Boolean {
        if (!needsRuntimePermission) return true
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun adapter(context: Context): BluetoothAdapter? = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }.getOrNull()

    /** Есть ли вообще с чем работать: модуль на месте и включён. */
    fun isEnabled(context: Context): Boolean = runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false)

    @SuppressLint("MissingPermission") // проверяется в hasPermission до вызова
    fun paired(context: Context): List<PairedDevice> {
        if (!hasPermission(context)) return emptyList()
        return runCatching {
            adapter(context)?.bondedDevices.orEmpty().map {
                PairedDevice(name = it.name ?: it.address, address = it.address)
            }.sortedBy { it.name }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    fun find(context: Context, address: String): BluetoothDevice? = runCatching {
        adapter(context)?.bondedDevices.orEmpty().firstOrNull { it.address == address }
    }.getOrNull()
}
