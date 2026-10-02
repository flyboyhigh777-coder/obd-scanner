package com.obdscanner.core

import com.obdscanner.core.elm.Elm
import com.obdscanner.core.elm.ObdProtocol
import com.obdscanner.core.session.ConnectOutcome
import com.obdscanner.core.session.ElmAdapter
import com.obdscanner.core.session.ObdChannel
import com.obdscanner.core.session.ObdSession
import com.obdscanner.core.sim.SimulatedEcu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Принудительный выбор протокола. Клоны ELM327 (в том числе Wi-Fi на
 * PIC18F25K80) автоопределение поддерживают, но перебирают протоколы заново на
 * каждом подключении - несколько секунд на пустом месте. Если протокол машины
 * известен, перебор не нужен; а если человек указал его неверно, приложение
 * обязано само вернуться к автоопределению, а не сказать "нет связи".
 */
class ProtocolSelectionTest {

    private class FakeClock(var millis: Long = 1000) {
        fun now() = millis
        fun sleep(ms: Long) { millis += ms }
    }

    /** Канал-посредник: пишет все команды в журнал и отдаёт ответы симулятора. */
    private class RecordingChannel(
        private val inner: ObdChannel,
        val commands: MutableList<String> = ArrayList(),
        /** Команды, после которых запрос данных будет отвечать "нет связи". */
        private val failWhileProtocol: String? = null
    ) : ObdChannel {

        private var blocked = false
        private val pending = StringBuilder()

        override fun write(bytes: ByteArray) {
            val command = String(bytes).trim().uppercase()
            commands.add(command)
            if (failWhileProtocol != null && command == failWhileProtocol) blocked = true
            if (failWhileProtocol != null && command == Elm.PROTOCOL_AUTO) blocked = false
            if (blocked && !command.startsWith("AT")) {
                // Так отвечает адаптер, которому задали протокол не той машины.
                pending.append("UNABLE TO CONNECT\r\r>")
                return
            }
            inner.write(bytes)
        }

        override fun read(dst: ByteArray): Int {
            if (pending.isNotEmpty()) {
                val n = minOf(dst.size, pending.length)
                for (i in 0 until n) dst[i] = pending[i].code.toByte()
                pending.delete(0, n)
                return n
            }
            return inner.read(dst)
        }

        override fun close() = inner.close()
    }

    /** Автоопределение: уходит ATSP0, как было. */
    @Test
    fun autoProtocolSendsAtsp0() {
        val clock = FakeClock()
        val channel = RecordingChannel(SimulatedEcu(clock = clock::now))
        val session = ObdSession(ElmAdapter(channel, clock::sleep, clock::now))
        assertTrue(session.connect() is ConnectOutcome.Connected)
        assertTrue("ATSP0 должен уйти: ${channel.commands}", channel.commands.contains("ATSP0"))
        // Агрессивный тайминг клонам вредит - должен уходить обычный
        assertTrue(channel.commands.contains("ATAT1"))
        assertFalse(channel.commands.contains("ATAT2"))
    }

    /** Заданный протокол уходит номером и автоопределение не запрашивается. */
    @Test
    fun forcedProtocolSkipsSearch() {
        val clock = FakeClock()
        val channel = RecordingChannel(SimulatedEcu(clock = clock::now))
        val session = ObdSession(
            ElmAdapter(channel, clock::sleep, clock::now),
            preferredProtocol = ObdProtocol.ISO15765_11_500
        )
        assertTrue(session.connect() is ConnectOutcome.Connected)
        assertTrue("ATSP6 должен уйти: ${channel.commands}", channel.commands.contains("ATSP6"))
        assertFalse("перебор не нужен", channel.commands.contains("ATSP0"))
    }

    /** Неверно заданный протокол: откат к автоопределению и всё равно связь. */
    @Test
    fun wrongForcedProtocolFallsBackToAuto() {
        val clock = FakeClock()
        // Машина не отвечает, пока задан протокол 3 (ISO 9141-2)
        val channel = RecordingChannel(SimulatedEcu(clock = clock::now), failWhileProtocol = "ATSP3")
        val session = ObdSession(
            ElmAdapter(channel, clock::sleep, clock::now),
            preferredProtocol = ObdProtocol.ISO9141_2
        )
        val outcome = session.connect()
        assertTrue("должно подключиться после отката: $outcome", outcome is ConnectOutcome.Connected)
        assertTrue(channel.commands.contains("ATSP3"))
        assertTrue("откат к автоопределению", channel.commands.contains("ATSP0"))
        assertEquals(ObdProtocol.ISO15765_11_500, (outcome as ConnectOutcome.Connected).protocol)
    }

    /** Команда ATSP собирается из кода протокола. */
    @Test
    fun protocolCommandText() {
        assertEquals("ATSP0", Elm.setProtocol(ObdProtocol.AUTO))
        assertEquals("ATSP6", Elm.setProtocol(ObdProtocol.ISO15765_11_500))
        assertEquals("ATSP5", Elm.setProtocol(ObdProtocol.ISO14230_FAST))
        assertEquals("ATSPA", Elm.setProtocol(ObdProtocol.SAE_J1939))
    }
}
