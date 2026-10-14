package com.obdscanner.core.elm

/**
 * Что за адаптер подключён и что он на самом деле умеет.
 *
 * Зачем это нужно отдельным экраном: половина жалоб на программы диагностики -
 * это жалобы на адаптер. Клон за 400 рублей отвечает "ELM327 v2.1", не понимает
 * половины команд и молча их проглатывает, выдавая "OK" на то, чего не делает.
 * Пока не видно, какие команды работают, непонятно, кто виноват в том, что
 * экран пустой: машина, программа или железка в разъёме.
 *
 * Поэтому проверка не спрашивает адаптер, кто он, а проверяет команды - те
 * самые, на которых держатся функции приложения.
 */
enum class AdapterChip(val ru: String, val en: String) {
    UNKNOWN("Неизвестный", "Unknown"),

    /** Настоящий ELM327 от ELM Electronics либо честно лицензированный. */
    ELM327("ELM327", "ELM327"),

    /** Клон: сообщает версию, которой у ELM327 никогда не было. */
    ELM327_CLONE("Клон ELM327", "ELM327 clone"),

    /** STN11xx/STN22xx - чип OBDLink, система команд ELM327 плюс свои ST. */
    STN("STN (OBDLink)", "STN (OBDLink)");

    val title: String get() = ObdText.pick(ru, en)
}

/** Одна проверка: что спрашивали и чем ответили. */
data class AdapterCheckItem(
    val ru: String,
    val en: String,
    val command: String,
    val supported: Boolean,
    val detail: String = ""
) {
    val title: String get() = ObdText.pick(ru, en)
}

data class AdapterInfo(
    /** Ответ на ATI: "ELM327 v1.5". */
    val description: String = "",
    /** Ответ на AT@1 - как адаптер себя называет. */
    val identifier: String = "",
    /** Серийный номер (AT@2 либо STSN). */
    val serial: String = "",
    /** Ответ на STDI - есть только у чипов STN. */
    val stnDescription: String = "",
    val chip: AdapterChip = AdapterChip.UNKNOWN,
    /** Версия из ответа ATI: "1.5", "2.2". */
    val version: String = "",
    val checks: List<AdapterCheckItem> = emptyList()
) {
    val missing: List<AdapterCheckItem> get() = checks.filterNot { it.supported }

    /**
     * Можно ли искать блоки вне OBD-II: для этого нужны свой заголовок запроса
     * и фильтр принимаемых адресов.
     */
    val canScanModules: Boolean
        get() = checks.firstOrNull { it.command.startsWith("ATSH") }?.supported == true &&
            checks.firstOrNull { it.command.startsWith("ATCRA") }?.supported == true

    val supportsVoltageCalibration: Boolean
        get() = checks.firstOrNull { it.command.startsWith("ATCV") }?.supported == true
}

/**
 * Опрос адаптера. [request] - функция "отправить команду, получить разбор
 * ответа"; так проверку можно прогнать и против живого адаптера, и против
 * симулятора в тесте.
 */
class AdapterProbe(private val request: (String) -> ElmReply) {

    fun identify(): AdapterInfo {
        val description = line(Elm.DEVICE_DESCRIPTION)
        val identifier = line(Elm.DEVICE_IDENTIFIER)
        val stn = line(Elm.STN_DEVICE_INFO)
        val serial = if (stn.isNotEmpty()) line(Elm.STN_SERIAL) else line(Elm.DEVICE_SERIAL)
        val version = versionOf(description)
        return AdapterInfo(
            description = description,
            identifier = identifier,
            serial = serial,
            stnDescription = stn,
            chip = chipOf(description, stn, version),
            version = version,
            checks = emptyList()
        )
    }

    /** Опознание плюс проверка команд. Это и показывает экран "Проверка адаптера". */
    fun check(): AdapterInfo = identify().copy(checks = probeCommands())

    private fun probeCommands(): List<AdapterCheckItem> = listOf(
        item("Заголовки ответов", "Response headers", Elm.HEADERS_ON),
        item("Длинные сообщения", "Long messages", Elm.ALLOW_LONG_MESSAGES),
        item("Подстройка таймаута", "Adaptive timing", Elm.ADAPTIVE_TIMING_1),
        item("Таймаут шины", "Bus timeout", Elm.timeout(200)),
        item("Свой заголовок запроса", "Custom request header", "ATSH7E0"),
        item("Фильтр принимаемых адресов", "Receive address filter", "ATCRA7E8"),
        item("Сырые кадры CAN", "Raw CAN frames", "ATCAF1"),
        item("Калибровка вольтметра", "Voltmeter calibration", "ATCV0000"),
        // Хвост: вернуть всё как было, чтобы проверка не оставила адаптер в
        // непонятном состоянии. Сама команда тоже проверка - её ответ важен.
        item("Сброс фильтров", "Clear filters", "ATCRA")
    )

    private fun item(ru: String, en: String, command: String): AdapterCheckItem {
        val reply = request(command)
        val ok = reply.isOk && !reply.isError
        return AdapterCheckItem(ru, en, command, ok, reply.firstLine)
    }

    private fun line(command: String): String {
        val reply = request(command)
        if (reply.isError) return ""
        val text = reply.firstLine.trim()
        // "?" адаптер печатает на незнакомую команду - это не ответ.
        return if (text == "?" || text.equals("OK", ignoreCase = true)) "" else text
    }

    private fun versionOf(description: String): String {
        val match = Regex("v?(\\d+\\.\\d+[a-z]?)", RegexOption.IGNORE_CASE).find(description)
        return match?.groupValues?.get(1).orEmpty()
    }

    private fun chipOf(description: String, stn: String, version: String): AdapterChip {
        if (stn.isNotEmpty()) return AdapterChip.STN
        if (!description.uppercase().contains("ELM32")) return AdapterChip.UNKNOWN
        // Версий 1.5 и 2.3 у ELM327 не выпускалось; их пишут на клонах, чтобы
        // выглядеть новее оригинала. Это самый надёжный признак из доступных
        // по одной команде - но именно признак, а не доказательство, поэтому
        // приложение пишет "похоже на клон", а не "клон".
        if (version in CLONE_VERSIONS) return AdapterChip.ELM327_CLONE
        return AdapterChip.ELM327
    }

    private companion object {
        val CLONE_VERSIONS = setOf("1.5", "2.3")
    }
}
