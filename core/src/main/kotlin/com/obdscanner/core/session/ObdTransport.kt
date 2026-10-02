package com.obdscanner.core.session

import com.obdscanner.core.elm.ElmReply
import java.io.Closeable

/**
 * Байтовый канал до адаптера. Ровно это и отличает Bluetooth от Wi-Fi и от
 * демо-режима; всё остальное - разбор протокола, опрос, ошибки - общее и живёт
 * в :core, поэтому покрыто обычными JVM-тестами.
 *
 * [read] не обязан ничего ждать: вернуть 0 - нормально, цикл в [ElmAdapter]
 * сам подождёт и попробует снова до истечения таймаута.
 */
interface ObdChannel : Closeable {
    fun write(bytes: ByteArray)
    fun read(dst: ByteArray): Int
}

/**
 * Разговор с ELM327: команда - строка, ответ - до символа приглашения '>'.
 *
 * Адаптер отвечает не мгновенно и не целиком: ответ приходит кусками, а признак
 * конца только один - приглашение. Поэтому читаем в цикле до '>' или до
 * таймаута. [sleep] и [now] вынесены в параметры, чтобы тесты шли мгновенно и
 * не зависели от реального времени.
 */
class ElmAdapter(
    private val channel: ObdChannel,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() }
) : Closeable {

    private val buffer = ByteArray(4096)

    /** Сколько ждать обычный запрос данных. */
    var defaultTimeoutMillis: Long = 1200

    /** Последний сырой ответ - показывается в "терминале" приложения. */
    var lastRaw: String = ""
        private set

    fun request(command: String, timeoutMillis: Long = defaultTimeoutMillis): ElmReply {
        channel.write((command + "\r").toByteArray(Charsets.US_ASCII))
        val raw = readUntilPrompt(timeoutMillis)
        lastRaw = raw
        return ElmReply.parse(raw, echo = command)
    }

    /** Выбрасывает всё, что адаптер мог не доотдать с прошлого раза. */
    fun drain() {
        while (channel.read(buffer) > 0) {
            // нечего делать - данные прошлого ответа нам не нужны
        }
    }

    private fun readUntilPrompt(timeoutMillis: Long): String {
        val deadline = now() + timeoutMillis
        val text = StringBuilder()
        while (now() < deadline) {
            val n = channel.read(buffer)
            if (n > 0) {
                text.append(String(buffer, 0, n, Charsets.US_ASCII))
                if (text.contains('>')) return text.toString()
            } else {
                sleep(4)
            }
        }
        return text.toString()
    }

    override fun close() = channel.close()
}
