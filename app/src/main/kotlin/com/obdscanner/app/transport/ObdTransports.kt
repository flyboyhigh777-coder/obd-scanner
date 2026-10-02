package com.obdscanner.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.obdscanner.core.session.ObdChannel
import com.obdscanner.core.sim.SimulatedEcu
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.math.min

/** Куда подключаться. */
sealed interface ConnectionTarget {
    /** Классический Bluetooth (профиль последовательного порта). */
    data class Bluetooth(val address: String, val name: String) : ConnectionTarget

    /** Bluetooth Low Energy: данные через характеристики GATT. */
    data class BluetoothLe(val address: String, val name: String) : ConnectionTarget

    data class Wifi(val host: String, val port: Int) : ConnectionTarget
    data object Demo : ConnectionTarget

    val title: String
        get() = when (this) {
            is Bluetooth -> name
            is BluetoothLe -> "$name (BLE)"
            is Wifi -> "$host:$port"
            Demo -> "демо"
        }
}

/**
 * Адаптер по Bluetooth. Почти все ELM327 - это Bluetooth Classic с профилем SPP
 * (последовательный порт поверх RFCOMM), поэтому UUID всегда один и тот же,
 * стандартный.
 *
 * [read] не блокируется: сначала спрашиваем `available()`, и если в сокете
 * ничего нет - честно возвращаем 0. Блокирующее чтение здесь было бы хуже:
 * ответ адаптера приходит кусками, и ждать "ещё байт" нужно до приглашения
 * '>', а не до конца потока - этим занимается ElmAdapter в :core.
 */
class BluetoothObdChannel(private val device: BluetoothDevice) : ObdChannel {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @SuppressLint("MissingPermission") // разрешение проверяется до создания канала
    fun open() {
        val created = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            created.connect()
            attach(created)
            return
        } catch (first: IOException) {
            runCatching { created.close() }
            // Дальше - две обходные попытки для клонов, по возрастанию
            // сомнительности. Сначала соединение без шифрования: часть
            // адаптеров не умеет сопряжение как положено и отвергает защищённый
            // канал. Потом прямой номер RFCOMM-канала через скрытый метод -
            // последнее средство, на исправном адаптере до него не доходит.
            val insecure = runCatching {
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            }.getOrNull()
            if (insecure != null) {
                attach(insecure)
                return
            }
            val fallback = openByChannelNumber() ?: throw first
            attach(fallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openByChannelNumber(): BluetoothSocket? = runCatching {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        val socket = method.invoke(device, 1) as BluetoothSocket
        socket.connect()
        socket
    }.getOrNull()

    private fun attach(connected: BluetoothSocket) {
        socket = connected
        input = connected.inputStream
        output = connected.outputStream
    }

    override fun write(bytes: ByteArray) {
        val stream = output ?: throw IOException("Канал не открыт")
        stream.write(bytes)
        stream.flush()
    }

    override fun read(dst: ByteArray): Int {
        val stream = input ?: return 0
        val available = stream.available()
        if (available <= 0) return 0
        return stream.read(dst, 0, min(dst.size, available))
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null; output = null; socket = null
    }

    companion object {
        /** Стандартный UUID профиля Serial Port Profile. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * Адаптер по Wi-Fi: обычный TCP-сокет. Такие адаптеры поднимают свою точку
 * доступа и слушают порт (чаще всего 192.168.0.10:35000).
 */
class WifiObdChannel(private val host: String, private val port: Int) : ObdChannel {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    fun open() {
        val created = Socket()
        created.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        // Короткий таймаут - страховка: читаем мы через available(), но если
        // прошивка адаптера всё-таки заблокирует чтение, поток не повиснет.
        created.soTimeout = 200
        created.tcpNoDelay = true
        socket = created
        input = created.getInputStream()
        output = created.getOutputStream()
    }

    override fun write(bytes: ByteArray) {
        val stream = output ?: throw IOException("Канал не открыт")
        stream.write(bytes)
        stream.flush()
    }

    override fun read(dst: ByteArray): Int {
        val stream = input ?: return 0
        return try {
            val available = stream.available()
            if (available <= 0) return 0
            stream.read(dst, 0, min(dst.size, available))
        } catch (timeout: java.net.SocketTimeoutException) {
            0
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        input = null; output = null; socket = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5000
    }
}

/**
 * Демо-режим: тот же симулятор ЭБУ, что и в тестах :core, но с небольшой
 * задержкой ответа - иначе приборы обновлялись бы мгновенно, и по экрану было
 * бы не видно, как ведёт себя приложение на настоящем, неторопливом адаптере.
 */
class DemoObdChannel(private val delayPerRequestMillis: Long = 25) : ObdChannel {

    private val ecu = SimulatedEcu()

    override fun write(bytes: ByteArray) {
        if (delayPerRequestMillis > 0) {
            runCatching { Thread.sleep(delayPerRequestMillis) }
        }
        ecu.write(bytes)
    }

    override fun read(dst: ByteArray): Int = ecu.read(dst)

    override fun close() = ecu.close()
}
