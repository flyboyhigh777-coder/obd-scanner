package com.obdscanner.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.obdscanner.core.elm.ObdText
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
 * Как именно открывать RFCOMM-канал до адаптера.
 *
 * Способов четыре, и это не теоретическая полнота: дешёвые клоны ELM327
 * по-разному врут о себе Bluetooth-стеку. Один не отдаёт запись о сервисе SPP
 * и открывается только по прямому номеру канала, другой отвергает защищённое
 * соединение, третий не умеет незащищённое. Перебор решает это сам, но
 * занимает до десятка секунд, поэтому способ, который сработал, можно
 * закрепить в настройках - связь тогда поднимается с первой попытки.
 */
enum class BluetoothConnectMethod(val key: String, val ru: String, val en: String) {
    AUTO("auto", "Автоматически", "Automatic"),
    SECURE_SPP("secure_spp", "Защищённый (по записи о сервисе)", "Secure (service record)"),
    INSECURE_SPP("insecure_spp", "Без шифрования (по записи о сервисе)", "Insecure (service record)"),
    INSECURE_CHANNEL("insecure_channel", "Без шифрования, канал 1", "Insecure, channel 1"),
    RAW_CHANNEL("raw_channel", "Напрямую, канал 1", "Direct, channel 1");

    val title: String get() = ObdText.pick(ru, en)

    companion object {
        fun fromKey(key: String?): BluetoothConnectMethod =
            entries.firstOrNull { it.key == key } ?: AUTO
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
class BluetoothObdChannel(
    private val device: BluetoothDevice,
    /** Способ соединения из настроек; [BluetoothConnectMethod.AUTO] - перебор. */
    private val method: BluetoothConnectMethod = BluetoothConnectMethod.AUTO
) : ObdChannel {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Каким способом соединение в итоге удалось - показывается на экране связи. */
    var usedMethod: BluetoothConnectMethod? = null
        private set

    @SuppressLint("MissingPermission") // разрешение проверяется до создания канала
    fun open() {
        // При выборе вручную пробуется ровно один способ: смысл настройки в
        // том, чтобы не перебирать. Перебор занимает до десятка секунд на
        // адаптере, который отвечает только четвёртым способом, и каждая
        // неудачная попытка оставляет Bluetooth-стек в задумчивом состоянии.
        val order = if (method == BluetoothConnectMethod.AUTO) AUTO_ORDER else listOf(method)
        var firstError: IOException? = null
        for (candidate in order) {
            val connected = try {
                connectWith(candidate)
            } catch (error: IOException) {
                if (firstError == null) firstError = error
                null
            }
            if (connected != null) {
                usedMethod = candidate
                attach(connected)
                return
            }
        }
        throw firstError ?: IOException("Bluetooth: не удалось открыть канал")
    }

    @SuppressLint("MissingPermission")
    private fun connectWith(method: BluetoothConnectMethod): BluetoothSocket? {
        val socket = when (method) {
            BluetoothConnectMethod.AUTO -> return null
            BluetoothConnectMethod.SECURE_SPP -> device.createRfcommSocketToServiceRecord(SPP_UUID)
            BluetoothConnectMethod.INSECURE_SPP -> device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            // Скрытые методы: в публичном Android SDK их нет, но они есть в
            // реализации с первых версий и работают там, где штатные не
            // помогают - у клонов, которые не отдают запись о сервисе SPP.
            BluetoothConnectMethod.INSECURE_CHANNEL -> reflective("createInsecureRfcommSocket")
            BluetoothConnectMethod.RAW_CHANNEL -> reflective("createRfcommSocket")
        } ?: return null
        return try {
            socket.connect()
            socket
        } catch (error: IOException) {
            // Неудачный сокет обязательно закрывается: иначе следующая попытка
            // упирается в занятый канал, и перебор способов теряет смысл.
            runCatching { socket.close() }
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    private fun reflective(name: String): BluetoothSocket? = runCatching {
        val handle = device.javaClass.getMethod(name, Int::class.javaPrimitiveType)
        handle.invoke(device, 1) as BluetoothSocket
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

        /**
         * Порядок перебора: от того, как положено, к тому, что работает.
         *
         * Сначала штатный защищённый канал по записи о сервисе, потом тот же
         * канал без шифрования (часть клонов не умеет сопряжение как
         * положено), и только потом прямой номер канала через скрытые методы.
         */
        val AUTO_ORDER: List<BluetoothConnectMethod> = listOf(
            BluetoothConnectMethod.SECURE_SPP,
            BluetoothConnectMethod.INSECURE_SPP,
            BluetoothConnectMethod.INSECURE_CHANNEL,
            BluetoothConnectMethod.RAW_CHANNEL
        )
    }
}

/**
 * Адаптер по Wi-Fi: обычный TCP-сокет. Такие адаптеры поднимают свою точку
 * доступа и слушают порт (чаще всего 192.168.0.10:35000).
 *
 * Главная тонкость - не в сокете, а в маршрутизации. У сети адаптера нет
 * интернета, и Android, увидев это, продолжает гонять трафик через мобильную
 * сеть: сокет на 192.168.0.10 уходит "в оператора" и никуда не доходит. Именно
 * поэтому в инструкциях к таким адаптерам пишут "отключите мобильный интернет".
 * Отключать ничего не нужно: сокет можно привязать к конкретной сети до
 * соединения - [bindToWifi] находит сеть Wi-Fi и привязывает сокет к ней.
 * Мобильный интернет при этом продолжает работать для всего остального.
 *
 * Если привязать не удалось (нет разрешения, не нашлась сеть), соединение всё
 * равно пробуется обычным способом - тогда остаётся старый способ: выключить
 * мобильные данные руками.
 */
class WifiObdChannel(
    private val host: String,
    private val port: Int,
    /** Нужен для привязки сокета к сети адаптера; без него привязки не будет. */
    private val context: Context? = null
) : ObdChannel {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Удалось ли привязать сокет к Wi-Fi - приложение показывает это подсказкой. */
    var boundToWifi: Boolean = false
        private set

    fun open() {
        val created = Socket()
        boundToWifi = bindToWifi(created)
        created.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        // Короткий таймаут - страховка: читаем мы через available(), но если
        // прошивка адаптера всё-таки заблокирует чтение, поток не повиснет.
        created.soTimeout = 200
        created.tcpNoDelay = true
        socket = created
        input = created.getInputStream()
        output = created.getOutputStream()
    }

    /**
     * Привязывает ещё не подключённый сокет к сети Wi-Fi.
     *
     * Сеть ищется перебором, а не берётся как "активная": активной Android
     * считает ту, через которую есть интернет, то есть как раз мобильную.
     * getAllNetworks с Android 12 помечен устаревшим в пользу подписки на
     * изменения сетей, но здесь нужен разовый поиск в момент подключения, и
     * заводить ради него колбэк с его жизненным циклом - сложнее без выигрыша.
     */
    @Suppress("DEPRECATION")
    private fun bindToWifi(socket: Socket): Boolean {
        val ctx = context ?: return false
        return runCatching {
            val manager = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
            val wifi = manager.allNetworks.firstOrNull { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return false
            wifi.bindSocket(socket)
            true
        }.getOrDefault(false)
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

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000

        /**
         * Типовые адреса Wi-Fi адаптеров ELM327. Разные партии прошиты
         * по-разному, а на корпусе адрес пишут редко - поэтому проще дать выбор,
         * чем заставлять искать его в интернете (которого в этот момент как раз
         * может не быть).
         */
        val KNOWN_ADDRESSES: List<Pair<String, Int>> = listOf(
            "192.168.0.10" to 35000,
            "192.168.0.11" to 35000,
            "192.168.1.5" to 35000,
            "192.168.4.1" to 35000,
            "10.0.0.10" to 35000
        )
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
