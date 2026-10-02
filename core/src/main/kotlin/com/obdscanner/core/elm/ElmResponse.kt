package com.obdscanner.core.elm

/**
 * Ошибки, о которых ELM327 сообщает текстом вместо данных. Разделять их важно:
 * NO_DATA - нормальная ситуация (машина не поддерживает этот PID), а
 * UNABLE_TO_CONNECT - уже повод показать пользователю "включите зажигание".
 */
enum class ElmError(val ru: String, val en: String) {
    NO_DATA("Нет данных", "No data"),
    UNABLE_TO_CONNECT(
        "Адаптер не видит шину автомобиля. Включите зажигание и проверьте, что адаптер вставлен в разъём до щелчка.",
        "The adapter cannot reach the vehicle bus. Switch the ignition on and reseat the adapter."
    ),
    BUS_INIT_ERROR("Не удалось инициализировать шину", "Bus initialisation failed"),
    BUS_BUSY("Шина занята", "Bus busy"),
    BUS_ERROR("Ошибка шины (проверьте разъём)", "Bus error"),
    CAN_ERROR("Ошибка CAN", "CAN error"),
    DATA_ERROR("Испорченный ответ (ошибка контрольной суммы)", "Corrupt response"),
    BUFFER_FULL("Буфер адаптера переполнен", "Adapter buffer full"),
    STOPPED("Запрос прерван", "Request stopped"),
    UNSUPPORTED_COMMAND("Адаптер не понял команду", "Adapter did not understand the command"),
    LOW_VOLTAGE("Низкое напряжение бортовой сети", "Low vehicle voltage"),
    TIMEOUT("Адаптер не ответил", "The adapter did not answer");

    val text: String get() = ObdText.pick(ru, en)
}

/**
 * Разобранный ответ адаптера: значимые строки без эха команды, приглашения ">"
 * и служебного шума - либо [error], если вместо данных пришёл текст об ошибке.
 */
data class ElmReply(
    val lines: List<String>,
    val error: ElmError? = null,
    val raw: String = ""
) {
    val isError: Boolean get() = error != null

    /** true, если это подтверждение AT-команды ("OK"). */
    val isOk: Boolean get() = lines.any { it.equals("OK", ignoreCase = true) }

    /** Первая содержательная строка - для AT-команд вроде ATRV ("12.4V") и ATI. */
    val firstLine: String get() = lines.firstOrNull().orEmpty()

    companion object {

        /** Строки, которые адаптер печатает между делом и которые не несут данных. */
        private val NOISE = setOf("SEARCHING...", "SEARCHING", "BUS INIT: OK", "BUS INIT:OK", "OK>", "")

        private val ERRORS: List<Pair<String, ElmError>> = listOf(
            "UNABLE TO CONNECT" to ElmError.UNABLE_TO_CONNECT,
            "NO DATA" to ElmError.NO_DATA,
            "BUS INIT: ERROR" to ElmError.BUS_INIT_ERROR,
            "BUS INIT:ERROR" to ElmError.BUS_INIT_ERROR,
            "BUS INIT: ..ERROR" to ElmError.BUS_INIT_ERROR,
            "BUS BUSY" to ElmError.BUS_BUSY,
            "BUS ERROR" to ElmError.BUS_ERROR,
            "CAN ERROR" to ElmError.CAN_ERROR,
            "DATA ERROR" to ElmError.DATA_ERROR,
            "BUFFER FULL" to ElmError.BUFFER_FULL,
            "FB ERROR" to ElmError.BUS_ERROR,
            "STOPPED" to ElmError.STOPPED,
            "LV RESET" to ElmError.LOW_VOLTAGE,
            "UNSUPPORTED" to ElmError.UNSUPPORTED_COMMAND
        )

        /**
         * @param raw всё, что пришло от адаптера на один запрос, включая эхо и ">".
         * @param echo текст отправленной команды - её эхо убирается из ответа, даже
         *   если ATE0 не подействовал (бывает сразу после ATZ).
         */
        fun parse(raw: String, echo: String? = null): ElmReply {
            val echoNorm = echo?.replace(" ", "")?.uppercase()
            val lines = raw.split('\r', '\n')
                .map { it.replace(">", "").trim() }
                .filter { it.isNotEmpty() }
                .filter { it.uppercase() !in NOISE }
                .filterNot { line ->
                    echoNorm != null && line.replace(" ", "").uppercase() == echoNorm
                }

            // "?" - единственный ответ целиком: адаптер не понял команду. Внутри
            // данных вопросительного знака быть не может, так что проверка точная.
            if (lines.any { it == "?" }) {
                return ElmReply(lines, ElmError.UNSUPPORTED_COMMAND, raw)
            }
            val upper = lines.joinToString(" ") { it.uppercase() }
            val error = ERRORS.firstOrNull { upper.contains(it.first) }?.second
            // "ERROR" без уточнения - уже после конкретных вариантов, чтобы
            // "CAN ERROR" не превратился в безликий BUS_ERROR.
            val resolved = error ?: if (upper.contains("ERROR")) ElmError.BUS_ERROR else null
            return ElmReply(lines, resolved, raw)
        }
    }
}

/**
 * Сборка полезной нагрузки OBD-ответа из того, что напечатал адаптер.
 *
 * ELM327 отдаёт данные в нескольких форматах, и все встречаются живьём:
 *  - один кадр, заголовки выключены: `41 0C 1A F8`;
 *  - длинный ответ (VIN, список ошибок), заголовки выключены - адаптер сам
 *    собирает ISO-TP и печатает общую длину и номера строк:
 *    `014` / `0: 49 02 01 31 44 34` / `1: 47 50 30 30 52 35`;
 *  - заголовки включены (ATH1): перед данными идёт CAN-ID, а за ним байт PCI -
 *    `7E8 03 41 05 7B` (один кадр), `7E8 10 14 49 02 ...` + `7E8 21 ...`
 *    (первый кадр ISO-TP и продолжение), которые нужно склеить самим.
 *
 * Разбор идёт не "по позициям", а поиском ожидаемого ответного байта
 * (`сервис + 0x40`) и эха PID за ним - так лишний заголовок, байт PCI или
 * вклинившаяся строка "SEARCHING..." не ломают результат. Служебные байты
 * ISO-TP (0x0L, 0x1LLL, 0x2N) однозначно отличаются от начала ответа: у ответа
 * старший полубайт всегда 4 (0x41, 0x43, 0x49...) либо 0x7F у отказа.
 */
object ObdPayload {

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it.isDigit() || it in 'A'..'F' }

    private fun hexToBytes(hex: String): IntArray {
        val out = ArrayList<Int>(hex.length / 2)
        var i = 0
        while (i + 1 < hex.length) {
            out.add(hex.substring(i, i + 2).toInt(16))
            i += 2
        }
        return out.toIntArray()
    }

    /** Байты одной строки ответа без CAN-заголовка. */
    private fun lineBytes(line: String): IntArray {
        val tokens = line.uppercase().split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return IntArray(0)
        // CAN-заголовок - отдельный токен из 3 (11 бит, "7E8") или 8 (29 бит,
        // "18DAF110") hex-символов; у данных токены по два символа.
        val skip = if (tokens.size > 1 && (tokens[0].length == 3 || tokens[0].length == 8) && isHex(tokens[0])) 1 else 0
        val hex = tokens.drop(skip).joinToString("")
        return if (isHex(hex)) hexToBytes(hex) else IntArray(0)
    }

    /**
     * Логические кадры ответа: длинный ответ собирается в один кадр, несколько
     * ЭБУ на шине дают несколько кадров.
     */
    fun frames(reply: ElmReply): List<IntArray> {
        // Формат "0:", "1:" - адаптер собрал длинный ответ сам, строка перед
        // ним с общей длиной ("014") данных не несёт.
        if (reply.lines.any { it.length > 1 && it[1] == ':' }) {
            val merged = ArrayList<Int>()
            for (line in reply.lines) {
                val colon = line.indexOf(':')
                if (colon == 1) {
                    merged.addAll(lineBytes(line.substring(2)).toList())
                }
            }
            return if (merged.isEmpty()) emptyList() else listOf(merged.toIntArray())
        }

        val out = ArrayList<IntArray>()
        var pending: ArrayList<Int>? = null   // накопитель первого кадра ISO-TP
        var pendingLeft = 0
        for (line in reply.lines) {
            val bytes = lineBytes(line)
            if (bytes.isEmpty()) continue
            val pci = bytes[0]
            when {
                // Продолжение ISO-TP: 0x21, 0x22, ... - дописываем в накопитель.
                pending != null && (pci and 0xF0) == 0x20 -> {
                    val chunk = bytes.drop(1).take(pendingLeft)
                    pending.addAll(chunk)
                    pendingLeft -= chunk.size
                    if (pendingLeft <= 0) {
                        out.add(pending.toIntArray()); pending = null
                    }
                }
                // Первый кадр ISO-TP: 0x1L LL - длина 12 бит, дальше 6 байт данных.
                (pci and 0xF0) == 0x10 && bytes.size >= 2 -> {
                    pending?.let { out.add(it.toIntArray()) }
                    val total = ((pci and 0x0F) shl 8) or bytes[1]
                    val first = bytes.drop(2).take(total)
                    pending = ArrayList(first)
                    pendingLeft = total - first.size
                    if (pendingLeft <= 0) {
                        out.add(pending!!.toIntArray()); pending = null
                    }
                }
                // Одиночный кадр ISO-TP: 0x0L, где L - число байт данных.
                (pci and 0xF0) == 0x00 && pci in 1..7 && bytes.size > 1 -> {
                    out.add(bytes.drop(1).take(pci).toIntArray())
                }
                // Заголовки выключены: в строке сразу данные ответа (0x41, 0x43, ...).
                else -> out.add(bytes)
            }
        }
        pending?.let { out.add(it.toIntArray()) }
        return out
    }

    /** Все байты ответа подряд - для разбора, которому кадры не важны. */
    fun bytes(reply: ElmReply): IntArray {
        val out = ArrayList<Int>()
        frames(reply).forEach { out.addAll(it.toList()) }
        return out.toIntArray()
    }

    /**
     * Данные ответа после `сервис+0x40` (и эха PID, если он был в запросе).
     *
     * @param expectedBytes сколько байт данных ожидается; null - "всё до конца
     *   кадра". Для режима 01 длина фиксирована по PID, и обрезка по ней
     *   отсекает нули, которыми кадр добит до 8 байт.
     */
    fun data(reply: ElmReply, service: Int, pid: Int? = null, expectedBytes: Int? = null): IntArray? {
        if (reply.isError) return null
        val answer = service + 0x40
        for (frame in frames(reply)) {
            for (i in frame.indices) {
                if (frame[i] != answer) continue
                if (pid != null && (i + 1 >= frame.size || frame[i + 1] != pid)) continue
                val from = i + 1 + (if (pid == null) 0 else 1)
                if (from > frame.size) continue
                val tail = frame.copyOfRange(from, frame.size)
                return if (expectedBytes != null && tail.size > expectedBytes) {
                    tail.copyOfRange(0, expectedBytes)
                } else {
                    tail
                }
            }
        }
        return null
    }

    /** Полезная нагрузка всех кадров, где ЭБУ ответил на этот сервис (режимы 03/07/0A). */
    fun allFrameData(reply: ElmReply, service: Int): List<IntArray> {
        if (reply.isError) return emptyList()
        val answer = service + 0x40
        val out = ArrayList<IntArray>()
        for (frame in frames(reply)) {
            // Ответный байт ищем только в начале кадра: внутри списка ошибок
            // байт 0x43 - это уже данные (код C0321 - это байты 43 21).
            val start = frame.indexOfFirst { it == answer }
            if (start in 0..1) out.add(frame.copyOfRange(start + 1, frame.size))
        }
        return out
    }

    /** true, если ЭБУ ответил отказом (`7F <сервис> <код>`). */
    fun isNegative(reply: ElmReply, service: Int): Boolean {
        val all = bytes(reply)
        for (i in 0 until all.size - 1) {
            if (all[i] == 0x7F && all[i + 1] == service) return true
        }
        return false
    }
}
