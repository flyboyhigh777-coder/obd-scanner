package com.obdscanner.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.obdscanner.core.session.ObdChannel
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Адаптер по Bluetooth Low Energy.
 *
 * Дешёвые ELM327 делятся на два поколения: классический Bluetooth с профилем
 * последовательного порта (его держит [BluetoothObdChannel]) и BLE, где никакого
 * "порта" нет вовсе - данные ходят через характеристики GATT. BLE-адаптеры к
 * тому же обычно не сопрягаются с телефоном заранее, поэтому их не видно в
 * списке сопряжённых устройств, и находить их приходится сканированием.
 *
 * Единого стандарта на то, КАКИЕ характеристики использовать, у этих адаптеров
 * нет: у каждого производителя свои UUID. Поэтому сначала проверяются известные
 * пары (их немного, и они покрывают большинство клонов), а если ни одна не
 * нашлась - берётся первая подходящая пара из того, что устройство объявило:
 * характеристика с разрешением на запись и характеристика с уведомлениями. Это
 * честнее, чем список моделей: неизвестный адаптер с нормальным GATT заработает
 * сам, без правки приложения.
 */
class BleObdChannel(
    private val context: Context,
    private val device: BluetoothDevice,
    private val connectTimeoutMillis: Long = 15_000
) : ObdChannel {

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    /** Принятые байты до того, как их забрал ElmAdapter. */
    private val incoming = StringBuilder()
    private val lock = Any()

    /** Держится ли соединение - приложение показывает это при автопереподключении. */
    @Volatile
    var connected = false
        private set

    @Volatile private var failure: String? = null
    private var servicesLatch = CountDownLatch(1)
    private var notifyLatch = CountDownLatch(1)

    /** Сколько байт влезает в один пакет: MTU минус три служебных байта ATT. */
    @Volatile private var payloadSize = 20

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    // MTU по умолчанию 23 байта, то есть 20 байт данных: команда
                    // "ATZ\r" влезает, а длинный ответ адаптера пришёл бы кусками.
                    if (!requestMtu(gatt, 185)) discoverServices(gatt)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    failure = failure ?: "BLE: соединение закрыто (status $status)"
                    servicesLatch.countDown()
                    notifyLatch.countDown()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) payloadSize = (mtu - 3).coerceIn(20, 512)
            discoverServices(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failure = "BLE: не удалось прочитать список сервисов (status $status)"
            } else {
                pickCharacteristics(gatt)
            }
            servicesLatch.countDown()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            notifyLatch.countDown()
        }

        // Android 13 и новее отдают значение прямо в колбэк...
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = accept(value)

        // ...а до Android 13 его надо забирать из самой характеристики.
        @Deprecated("Нужен для Android 12 и старше")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { accept(it) }
        }
    }

    private fun accept(value: ByteArray) {
        if (value.isEmpty()) return
        synchronized(lock) { incoming.append(String(value, Charsets.US_ASCII)) }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT проверяется до создания канала
    private fun requestMtu(gatt: BluetoothGatt, mtu: Int): Boolean = runCatching { gatt.requestMtu(mtu) }
        .getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun discoverServices(gatt: BluetoothGatt) {
        if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
            failure = "BLE: устройство не дало прочитать сервисы"
            servicesLatch.countDown()
        }
    }

    /** Выбор пары характеристик: сначала известные, потом любая годная. */
    private fun pickCharacteristics(gatt: BluetoothGatt) {
        for ((serviceUuid, writeUuid, notifyUuid) in KNOWN_TRIPLES) {
            val service = gatt.getService(serviceUuid) ?: continue
            val write = service.getCharacteristic(writeUuid) ?: continue
            val notify = service.getCharacteristic(notifyUuid) ?: continue
            writeCharacteristic = write
            notifyCharacteristic = notify
            return
        }
        // Ничего знакомого: ищем по свойствам.
        for (service in gatt.services.orEmpty()) {
            val write = service.characteristics.orEmpty().firstOrNull { it.isWritable }
            val notify = service.characteristics.orEmpty().firstOrNull { it.isNotifying }
            if (write != null && notify != null) {
                writeCharacteristic = write
                notifyCharacteristic = notify
                return
            }
        }
        failure = "BLE: у устройства нет пары характеристик «запись + уведомления» - " +
            "похоже, это не адаптер OBD-II"
    }

    private val BluetoothGattCharacteristic.isWritable: Boolean
        get() = properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    private val BluetoothGattCharacteristic.isNotifying: Boolean
        get() = properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    @SuppressLint("MissingPermission")
    fun open() {
        servicesLatch = CountDownLatch(1)
        notifyLatch = CountDownLatch(1)
        failure = null
        val connection = device.connectGatt(context, false, callback)
            ?: throw IOException("BLE: не удалось начать соединение")
        gatt = connection
        if (!servicesLatch.await(connectTimeoutMillis, TimeUnit.MILLISECONDS)) {
            close()
            throw IOException("BLE: адаптер не ответил за ${connectTimeoutMillis / 1000} с")
        }
        failure?.let { close(); throw IOException(it) }
        val notify = notifyCharacteristic ?: run { close(); throw IOException("BLE: не найден канал приёма") }
        if (!connection.setCharacteristicNotification(notify, true)) {
            close(); throw IOException("BLE: адаптер не включил уведомления")
        }
        // Сами уведомления включаются записью в служебный дескриптор CCCD -
        // без неё характеристика молчит, хотя setCharacteristicNotification вернул true.
        notify.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)?.let { descriptor ->
            writeDescriptor(connection, descriptor)
            notifyLatch.await(3000, TimeUnit.MILLISECONDS)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeDescriptor(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            connection.writeDescriptor(descriptor, value)
        } else {
            descriptor.value = value
            connection.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun write(bytes: ByteArray) {
        val connection = gatt ?: throw IOException("BLE: канал закрыт")
        val characteristic = writeCharacteristic ?: throw IOException("BLE: не найден канал передачи")
        val writeType = if (characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, min(offset + payloadSize, bytes.size))
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                connection.writeCharacteristic(characteristic, chunk, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.value = chunk
                characteristic.writeType = writeType
                connection.writeCharacteristic(characteristic)
            }
            if (!ok) throw IOException("BLE: адаптер не принял команду")
            offset += chunk.size
        }
    }

    override fun read(dst: ByteArray): Int = synchronized(lock) {
        if (incoming.isEmpty()) return 0
        val count = min(dst.size, incoming.length)
        for (i in 0 until count) dst[i] = incoming[i].code.toByte()
        incoming.delete(0, count)
        count
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        connected = false
        synchronized(lock) { incoming.setLength(0) }
    }

    private companion object {
        /** Дескриптор включения уведомлений - один и тот же во всём Bluetooth. */
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun short(value: String): UUID = UUID.fromString("0000$value-0000-1000-8000-00805f9b34fb")

        /**
         * Известные тройки «сервис, запись, уведомления» у BLE-адаптеров
         * ELM327. Список не истина в последней инстанции - он просто избавляет
         * от перебора на самых частых клонах; всё остальное ловит разбор по
         * свойствам характеристик.
         */
        val KNOWN_TRIPLES: List<Triple<UUID, UUID, UUID>> = listOf(
            // Самый распространённый вариант (Vgate iCar, множество безымянных клонов)
            Triple(short("fff0"), short("fff2"), short("fff1")),
            // Модули на HM-10 и его копиях: одна характеристика и на запись, и на приём
            Triple(short("ffe0"), short("ffe1"), short("ffe1")),
            // Вариант с «обратными» номерами
            Triple(short("18f0"), short("2af1"), short("2af0")),
            // Nordic UART Service - встречается у адаптеров на чипах Nordic
            Triple(
                UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
            )
        )
    }
}
