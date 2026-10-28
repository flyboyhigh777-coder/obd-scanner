package com.obdscanner.core.modules

import com.obdscanner.core.dtc.Dtc
import com.obdscanner.core.dtc.DtcCodec
import com.obdscanner.core.elm.ObdText

/**
 * Блоки, которых нет в OBD-II.
 *
 * Стандарт OBD-II - это про выхлоп. Режимы 03/07/0A спрашивают только блок
 * управления двигателем, и больше ни один блок на них не отвечает: лампа ABS
 * на панели горит, а приложение честно показывает "ошибок нет", потому что у
 * двигателя их действительно нет.
 *
 * ABS, подушки, АКПП, электроусилитель сидят на той же шине, но со своими
 * адресами и отвечают на другие сервисы - UDS (ISO 14229) или KWP2000
 * (ISO 14230). Адреса производитель нигде не публикует, поэтому единственный
 * честный способ их найти - опросить диапазон диагностических адресов и
 * посмотреть, кто отзовётся.
 *
 * Всё здесь - только чтение. Сервис 19/18 (прочитать коды) и 3E (блок, ты
 * жив?) ничего в блоке не меняют. Стирания чужих кодов тут нет и не будет:
 * прерванная запись в блок ABS - это эвакуатор.
 */

/** Что ответил блок на запрос кодов. */
sealed interface ModuleReply {
    /** Коды (возможно, пустой список - блок ответил, но кодов у него нет). */
    data class Codes(val dtcs: List<ModuleDtc>, val protocol: ModuleProtocol) : ModuleReply

    /**
     * Блок есть, но отвечать отказался: "7F <сервис> <причина>". Для поиска
     * блоков это такой же успех - кто-то на адресе живёт.
     */
    data class Refused(val nrc: Int) : ModuleReply {
        val reason: String
            get() = ObdText.pick(
                NRC_RU[nrc] ?: "отказ 0x%02X".format(nrc),
                NRC_EN[nrc] ?: "refused 0x%02X".format(nrc),
            )
    }
}

/** Коды отказа UDS (ISO 14229, таблица NRC) человеческими словами. */
private val NRC_RU = mapOf(
    0x10 to "блок отклонил запрос",
    0x11 to "блок не умеет читать коды этим сервисом",
    0x12 to "блок не понял форму запроса",
    0x13 to "неверная длина запроса",
    0x22 to "сейчас нельзя: блок занят или условия не те",
    0x31 to "запрос вне диапазона",
    0x33 to "нужен доступ по ключу производителя",
    0x78 to "блок просит подождать",
    0x7E to "сервис не работает в этом сеансе",
    0x7F to "сервис не работает в этом сеансе",
)

private val NRC_EN = mapOf(
    0x10 to "general reject",
    0x11 to "service not supported",
    0x12 to "sub-function not supported",
    0x13 to "wrong request length",
    0x22 to "conditions not correct",
    0x31 to "request out of range",
    0x33 to "security access denied",
    0x78 to "response pending",
    0x7E to "service not supported in active session",
    0x7F to "service not supported in active session",
)

/** Каким языком блок говорит: современный UDS или старый KWP2000. */
enum class ModuleProtocol(val ru: String, val en: String) {
    UDS("UDS (ISO 14229)", "UDS (ISO 14229)"),
    KWP("KWP2000 (ISO 14230)", "KWP2000 (ISO 14230)");

    val title: String get() = ObdText.pick(ru, en)
}

/**
 * Код из блока вне OBD-II.
 *
 * У UDS код трёхбайтный: первые два байта - как в OBD-II (C0031), третий
 * уточняет отказ (обрыв, замыкание на массу, значение вне диапазона) и
 * пишется через дефис: C0031-11. У KWP2000 третьего байта нет.
 */
data class ModuleDtc(
    val code: String,
    val failureType: Int? = null,
    val status: Int? = null,
) {
    /** Полный код так, как его печатают в сервисной документации. */
    val full: String get() = if (failureType != null) "%s-%02X".format(code, failureType) else code

    /** Неисправность есть прямо сейчас (бит 0 статуса). */
    val active: Boolean? get() = status?.let { it and 0x01 == 0x01 }

    /** Код подтверждён и записан в память (бит 3). */
    val confirmed: Boolean? get() = status?.let { it shr 3 and 0x01 == 0x01 }

    /** Блок просит зажечь лампу на панели (бит 7). */
    val lampRequested: Boolean? get() = status?.let { it shr 7 and 0x01 == 0x01 }

    /** Описание берётся из общего каталога - там есть и C/B/U-коды. */
    val described: Dtc get() = Dtc(code)

    /**
     * Чем именно отказало - словами, или null, если сказать нечего.
     *
     * Третий байт кода UDS - это не "ещё один номер", а характер отказа:
     * обрыв, замыкание на массу, значение вне диапазона. Таблица общая
     * (ISO 14229-1, приложение D), поэтому её можно расшифровать для любой
     * машины. Байт 00 означает "уточнения нет" - под каждым кодом его писать
     * незачем, а в списке из двадцати кодов одинаковая строка под каждым
     * просто заслоняет сами коды.
     */
    val failureText: String?
        get() {
            val type = failureType ?: return null
            if (type == 0x00) return null
            return ObdText.pick(
                "Как отказало: " +
                    (FAILURE_RU[type] ?: "код %02X, расшифровка у производителя".format(type)),
                "Failure type: " +
                    (FAILURE_EN[type] ?: "%02X, meaning is the manufacturer's".format(type)),
            )
        }
}

/**
 * Характер отказа из третьего байта кода UDS (ISO 14229-1, приложение D).
 *
 * Здесь не весь список, а то, что реально встречается в машинах: цепь,
 * сигнал, исполнительный механизм. Чего нет - показывается номером, и это
 * честнее выдуманной расшифровки.
 */
private val FAILURE_RU = mapOf(
    0x01 to "общая электрическая неисправность",
    0x02 to "неисправность сигнала",
    0x04 to "внутренняя ошибка блока",
    0x08 to "нет связи по шине",
    0x09 to "неисправность самого датчика или механизма",
    0x11 to "замыкание на массу",
    0x12 to "замыкание на плюс",
    0x13 to "обрыв цепи",
    0x14 to "обрыв или замыкание на массу",
    0x15 to "обрыв или замыкание на плюс",
    0x16 to "напряжение ниже нормы",
    0x17 to "напряжение выше нормы",
    0x18 to "ток ниже нормы",
    0x19 to "ток выше нормы",
    0x1A to "сопротивление ниже нормы",
    0x1B to "сопротивление выше нормы",
    0x1C to "напряжение вне диапазона",
    0x1D to "ток вне диапазона",
    0x21 to "сигнал слабее нормы",
    0x22 to "сигнал сильнее нормы",
    0x23 to "сигнал завис на низком уровне",
    0x24 to "сигнал завис на высоком уровне",
    0x29 to "сигнал недостоверен",
    0x2B to "сигнал пропадает",
    0x31 to "сигнала нет",
    0x36 to "частота сигнала не та",
    0x41 to "механизм заклинил",
    0x42 to "механизм заклинил в открытом положении",
    0x43 to "механизм заклинил в закрытом положении",
    0x45 to "механизм заблокирован",
    0x49 to "перегрев механизма",
    0x51 to "нет калибровки",
    0x55 to "блок не запрограммирован",
    0x62 to "показания не сходятся с другими",
    0x64 to "значение неправдоподобно",
    0x73 to "механическая неисправность",
    0x81 to "испорченные данные по шине",
    0x87 to "сообщение по шине не приходит",
    0x92 to "работает не так, как должно",
    0x93 to "не работает",
    0x98 to "перегрев",
)

private val FAILURE_EN = mapOf(
    0x01 to "general electrical failure",
    0x02 to "general signal failure",
    0x04 to "internal module failure",
    0x08 to "bus signal failure",
    0x09 to "component failure",
    0x11 to "short to ground",
    0x12 to "short to battery",
    0x13 to "open circuit",
    0x14 to "open or short to ground",
    0x15 to "open or short to battery",
    0x16 to "voltage below threshold",
    0x17 to "voltage above threshold",
    0x18 to "current below threshold",
    0x19 to "current above threshold",
    0x1A to "resistance below threshold",
    0x1B to "resistance above threshold",
    0x1C to "voltage out of range",
    0x1D to "current out of range",
    0x21 to "signal amplitude below minimum",
    0x22 to "signal amplitude above maximum",
    0x23 to "signal stuck low",
    0x24 to "signal stuck high",
    0x29 to "signal invalid",
    0x2B to "signal erratic",
    0x31 to "no signal",
    0x36 to "signal frequency incorrect",
    0x41 to "actuator stuck",
    0x42 to "actuator stuck open",
    0x43 to "actuator stuck closed",
    0x45 to "actuator blocked",
    0x49 to "actuator over temperature",
    0x51 to "missing calibration",
    0x55 to "not programmed",
    0x62 to "signal compare failure",
    0x64 to "signal implausible",
    0x73 to "mechanical failure",
    0x81 to "invalid serial data received",
    0x87 to "missing message",
    0x92 to "performance or incorrect operation",
    0x93 to "no operation",
    0x98 to "over temperature",
)

/** Найденный блок: по какому адресу живёт и что рассказал. */
data class FoundModule(
    val address: Int,
    val reply: ModuleReply,
) {
    val hex: String get() = "%03X".format(address)

    val dtcs: List<ModuleDtc> get() = (reply as? ModuleReply.Codes)?.dtcs.orEmpty()

    /**
     * К какой системе относятся коды - по первой букве.
     *
     * Это надёжнее любого списка адресов: адреса производитель меняет от
     * модели к модели, а буква кода стандартна. C - шасси (ABS, ESP),
     * B - кузов (подушки, свет), U - связь между блоками.
     */
    val systems: List<String>
        get() = dtcs.mapNotNull { it.code.firstOrNull() }.distinct().map { letter ->
            when (letter) {
                'C' -> ObdText.pick("тормоза и шасси (ABS, ESP)", "chassis (ABS, ESP)")
                'B' -> ObdText.pick("кузов и салон (подушки, свет)", "body (airbags, lighting)")
                'U' -> ObdText.pick("связь между блоками", "inter-module communication")
                else -> ObdText.pick("двигатель и КПП", "powertrain")
            }
        }
}

object ModuleScan {

    /** UDS: прочитать все коды по маске статуса (сервис 19, подфункция 02). */
    const val UDS_READ_DTC = "1902FF"

    /** KWP2000: то же самое (сервис 18). */
    const val KWP_READ_DTC = "1800FF00"

    /**
     * Диапазон диагностических адресов 11-битной CAN.
     *
     * Так договорились: запросы к блокам идут в 0x700-0x7EF, причём
     * 0x7E0-0x7E7 отданы блокам, которые отвечают и на обычный OBD-II.
     * Остальное - всё прочее железо машины. За пределы этого диапазона
     * приложение не лезет: там живёт рабочий обмен между блоками, и слать
     * туда что попало незачем.
     */
    const val FIRST_ADDRESS = 0x700
    const val LAST_ADDRESS = 0x7E7

    /**
     * Ответный адрес по умолчанию - на восемь больше запросного. Так устроена
     * вся 11-битная диагностика: 7E0 спрашивает, 7E8 отвечает.
     */
    fun responseFor(request: Int): Int = request + 8

    /** Блоки двигателя и КПП видно и обычным OBD-II - их в находки не пишем. */
    fun isStandardObd(address: Int): Boolean = address in 0x7E0..0x7E7

    /**
     * Разбор ответа блока.
     *
     * @return null, если в кадрах нет ничего похожего на ответ - значит по
     *   этому адресу никого нет.
     */
    fun parse(frames: List<IntArray>): ModuleReply? {
        for (frame in frames) {
            refusal(frame)?.let { return it }
            uds(frame)?.let { return it }
            kwp(frame)?.let { return it }
        }
        return null
    }

    /** "7F <сервис> <причина>" - блок есть, но отвечать не стал. */
    private fun refusal(frame: IntArray): ModuleReply? {
        val at = frame.indexOfFirst { it == 0x7F }
        if (at < 0 || at > 1 || at + 2 >= frame.size) return null
        if (frame[at + 1] != 0x19 && frame[at + 1] != 0x18) return null
        return ModuleReply.Refused(frame[at + 2])
    }

    /** "59 02 <маска> [3 байта кода + статус]..." */
    private fun uds(frame: IntArray): ModuleReply? {
        val at = frame.indexOfFirst { it == 0x59 }
        if (at < 0 || at > 1 || at + 1 >= frame.size) return null
        if (frame[at + 1] != 0x02) return null
        // За подфункцией идёт байт доступных битов статуса - это не код.
        var i = at + 3
        val out = ArrayList<ModuleDtc>()
        while (i + 2 < frame.size) {
            val hi = frame[i]
            val mid = frame[i + 1]
            val lo = frame[i + 2]
            val status = frame.getOrNull(i + 3)
            if (!isPadding(hi, mid, lo)) {
                out.add(ModuleDtc(DtcCodec.decode(hi, mid), lo, status))
            }
            i += 4
        }
        return ModuleReply.Codes(merge(out), ModuleProtocol.UDS)
    }

    /** "58 <сколько> [2 байта кода + статус]..." */
    private fun kwp(frame: IntArray): ModuleReply? {
        val at = frame.indexOfFirst { it == 0x58 }
        if (at < 0 || at > 1 || at + 1 >= frame.size) return null
        var i = at + 2
        val out = ArrayList<ModuleDtc>()
        while (i + 1 < frame.size) {
            val hi = frame[i]
            val lo = frame[i + 1]
            val status = frame.getOrNull(i + 2)
            if (!isPadding(hi, lo)) out.add(ModuleDtc(DtcCodec.decode(hi, lo), null, status))
            i += 3
        }
        return ModuleReply.Codes(merge(out), ModuleProtocol.KWP)
    }

    /**
     * Заполнитель, а не код.
     *
     * Кадр CAN всегда длиной 8 байт, и хвост сообщения добивают одним и тем
     * же байтом - 00, AA, 55 или FF. Три одинаковых байта подряд из этого
     * набора - это добивка: именно так в списке кодов появляется B2AAA-AA,
     * которого в машине нет.
     */
    private val PADDING = setOf(0x00, 0xAA, 0x55, 0xFF)

    private fun isPadding(vararg bytes: Int): Boolean =
        bytes.first() in PADDING && bytes.all { it == bytes.first() }

    /**
     * Один код - одна строка.
     *
     * Блок может прислать один и тот же код дважды (так бывает, когда на
     * запрос отвечают два блока сразу), и человек видит в списке три
     * одинаковых строки с разными значками. Статусы при этом складываем: если
     * хоть в одном ответе код горит прямо сейчас - значит горит.
     */
    private fun merge(dtcs: List<ModuleDtc>): List<ModuleDtc> {
        val byCode = LinkedHashMap<String, ModuleDtc>()
        for (dtc in dtcs) {
            val was = byCode[dtc.full]
            byCode[dtc.full] = if (was == null) dtc else was.copy(
                status = when {
                    was.status == null -> dtc.status
                    dtc.status == null -> was.status
                    else -> was.status or dtc.status
                }
            )
        }
        return byCode.values.toList()
    }
}
