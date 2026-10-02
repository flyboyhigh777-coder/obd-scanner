package com.obdscanner.app.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Как адаптер подключается - от этого зависит и транспорт, и права. */
enum class LinkKind { CLASSIC, BLE }

/** Найденный адаптер. */
data class DiscoveredDevice(
    val name: String,
    val address: String,
    val kind: LinkKind,
    /** Уровень сигнала, дБм - только для BLE; помогает отличить свой адаптер от чужого. */
    val rssi: Int? = null,
    val paired: Boolean = false
)

/**
 * Поиск адаптеров: сопряжённые классические плюс сканирование BLE.
 *
 * Почему нужно и то, и другое: классический ELM327 сопрягается с телефоном
 * заранее и виден в списке сопряжённых, а BLE-адаптер обычно не сопрягается
 * вовсе - его видно только во время сканирования. Приложение не угадывает тип
 * по названию, а показывает оба списка и помечает, кто есть кто.
 *
 * Права:
 *  - Android 12+ (API 31): BLUETOOTH_CONNECT для работы с устройством и
 *    BLUETOOTH_SCAN для сканирования. В манифесте у BLUETOOTH_SCAN стоит
 *    neverForLocation, поэтому доступ к местоположению НЕ требуется.
 *  - Android 8-11: сканирование BLE на уровне платформы требует
 *    ACCESS_FINE_LOCATION - так решили в Android, потому что по маячкам можно
 *    определять местоположение. Приложению геопозиция не нужна и никуда не
 *    отправляется; разрешение спрашивается только при нажатии "искать BLE" и
 *    только на этих версиях.
 */
object BluetoothScanner {

    val needsRuntimeBluetoothPermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Разрешения, нужные именно для сканирования BLE на этой версии Android. */
    val scanPermissions: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Разрешения для соединения с уже выбранным устройством. */
    val connectPermissions: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }

    fun hasPermissions(context: Context, permissions: List<String>): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun adapter(context: Context): BluetoothAdapter? = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }.getOrNull()

    fun isEnabled(context: Context): Boolean = runCatching { adapter(context)?.isEnabled == true }
        .getOrDefault(false)

    /** Поддерживает ли телефон BLE вообще. */
    fun supportsBle(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<DiscoveredDevice> {
        if (!hasPermissions(context, connectPermissions)) return emptyList()
        return runCatching {
            adapter(context)?.bondedDevices.orEmpty().map { device ->
                DiscoveredDevice(
                    name = device.name ?: device.address,
                    address = device.address,
                    // Сопряжённое устройство может быть и BLE - тогда тип виден по его классу.
                    kind = if (device.type == BluetoothDevice.DEVICE_TYPE_LE) LinkKind.BLE else LinkKind.CLASSIC,
                    paired = true
                )
            }.sortedBy { it.name }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    fun findDevice(context: Context, address: String): BluetoothDevice? = runCatching {
        adapter(context)?.getRemoteDevice(address)
    }.getOrNull()

    /**
     * Сканирование BLE. Возвращает «ручку», которой сканирование останавливают:
     * оставленный включённым сканер сажает батарею, поэтому экран обязан его
     * выключить, уходя.
     */
    @SuppressLint("MissingPermission")
    fun startBleScan(context: Context, onFound: (DiscoveredDevice) -> Unit): BleScan? {
        if (!hasPermissions(context, scanPermissions)) return null
        val scanner = adapter(context)?.bluetoothLeScanner ?: return null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: return
                onFound(
                    DiscoveredDevice(
                        name = name,
                        address = device.address,
                        kind = LinkKind.BLE,
                        rssi = result.rssi
                    )
                )
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return runCatching {
            scanner.startScan(null, settings, callback)
            BleScan(scanner, callback)
        }.getOrNull()
    }

    /** Включённое сканирование, которое нужно остановить. */
    class BleScan(
        private val scanner: android.bluetooth.le.BluetoothLeScanner,
        private val callback: ScanCallback
    ) {
        @SuppressLint("MissingPermission")
        fun stop() {
            runCatching { scanner.stopScan(callback) }
        }
    }
}
