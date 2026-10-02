package com.obdscanner.core.elm

/**
 * Протоколы шины OBD-II, которые умеет адаптер ELM327. Код - та самая hex-цифра,
 * которой протокол обозначается в командах `AT SP <n>` (set protocol) и в ответе
 * на `AT DPN` (describe protocol by number).
 *
 * Тексты (ru/en) лежат здесь, а не в ресурсах приложения, сознательно: это часть
 * словаря протокола, который целиком покрыт тестами в :core, а не элемент
 * оформления. Приложение выбирает нужный язык в [ObdText.pick].
 */
enum class ObdProtocol(val elmCode: Char, val ru: String, val en: String) {
    AUTO('0', "Автоопределение", "Automatic"),
    J1850_PWM('1', "SAE J1850 PWM, 41.6 кбит/с (Ford)", "SAE J1850 PWM (41.6 kbit/s)"),
    J1850_VPW('2', "SAE J1850 VPW, 10.4 кбит/с (GM)", "SAE J1850 VPW (10.4 kbit/s)"),
    ISO9141_2('3', "ISO 9141-2, 5 бод init", "ISO 9141-2"),
    ISO14230_5BAUD('4', "ISO 14230-4 KWP, 5 бод init", "ISO 14230-4 KWP (5 baud init)"),
    ISO14230_FAST('5', "ISO 14230-4 KWP, fast init", "ISO 14230-4 KWP (fast init)"),
    ISO15765_11_500('6', "ISO 15765-4 CAN, 11 бит / 500 кбит/с", "ISO 15765-4 CAN (11 bit, 500 kbit/s)"),
    ISO15765_29_500('7', "ISO 15765-4 CAN, 29 бит / 500 кбит/с", "ISO 15765-4 CAN (29 bit, 500 kbit/s)"),
    ISO15765_11_250('8', "ISO 15765-4 CAN, 11 бит / 250 кбит/с", "ISO 15765-4 CAN (11 bit, 250 kbit/s)"),
    ISO15765_29_250('9', "ISO 15765-4 CAN, 29 бит / 250 кбит/с", "ISO 15765-4 CAN (29 bit, 250 kbit/s)"),
    SAE_J1939('A', "SAE J1939 CAN, 29 бит / 250 кбит/с", "SAE J1939 CAN (29 bit, 250 kbit/s)"),
    USER1('B', "Пользовательский 1 (CAN 11 бит)", "User 1 (CAN 11 bit)"),
    USER2('C', "Пользовательский 2 (CAN 11 бит)", "User 2 (CAN 11 bit)");

    val isCan: Boolean
        get() = this == ISO15765_11_500 || this == ISO15765_29_500 ||
            this == ISO15765_11_250 || this == ISO15765_29_250 ||
            this == SAE_J1939 || this == USER1 || this == USER2

    companion object {
        /**
         * Разбирает ответ на `AT DPN`. Адаптер отвечает либо одной цифрой ("6"),
         * либо с префиксом "A" в режиме автоопределения ("A6" - "автоматически
         * выбран протокол 6").
         */
        fun fromDpn(reply: String): ObdProtocol? {
            val code = reply.trim().uppercase().removePrefix("A").trim().firstOrNull() ?: return null
            return entries.firstOrNull { it.elmCode == code }
        }
    }
}

/** Команды AT для адаптера и сервисы (режимы) самого OBD-II. */
object Elm {
    const val RESET = "ATZ"
    const val ECHO_OFF = "ATE0"
    const val LINEFEEDS_OFF = "ATL0"
    const val SPACES_OFF = "ATS0"
    const val HEADERS_OFF = "ATH0"
    const val HEADERS_ON = "ATH1"
    const val ADAPTIVE_TIMING_2 = "ATAT2"
    const val PROTOCOL_AUTO = "ATSP0"
    const val DESCRIBE_PROTOCOL_NUMBER = "ATDPN"
    const val READ_VOLTAGE = "ATRV"
    const val DEVICE_DESCRIPTION = "ATI"

    /** `AT ST hh` - таймаут ожидания ответа шины, шаг 4 мс (hh=32 -> ~200 мс). */
    fun timeout(millis: Int): String = "ATST%02X".format((millis / 4).coerceIn(1, 255))

    /** Сервис (режим) OBD-II. */
    object Service {
        const val CURRENT_DATA = 0x01
        const val FREEZE_FRAME = 0x02
        const val STORED_DTC = 0x03
        const val CLEAR_DTC = 0x04
        const val O2_TESTS = 0x05
        const val PENDING_DTC = 0x07
        const val VEHICLE_INFO = 0x09
        const val PERMANENT_DTC = 0x0A
    }

    /** Текст запроса: "010C" (режим 01, PID 0C) или "03" (режим без PID). */
    fun request(service: Int, pid: Int? = null): String =
        if (pid == null) "%02X".format(service) else "%02X%02X".format(service, pid)
}

/** Выбор языка для текстов, живущих в :core (протоколы, коды ошибок, типы топлива). */
object ObdText {
    /** true - русский. Приложение передаёт сюда результат проверки локали. */
    var russian: Boolean = true

    fun pick(ru: String, en: String): String = if (russian) ru else en
}
