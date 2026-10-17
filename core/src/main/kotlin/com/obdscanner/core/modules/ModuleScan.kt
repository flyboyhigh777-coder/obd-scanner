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

    /** Статус одной фразой: ошибка есть сейчас, подтверждена, только история... */
    val statusText: String? get() = UdsStatus.describe(status)
}

/**
 * Байт статуса кода UDS (ISO 14229, DTCStatusMask) человеческими словами.
 *
 * Зачем это нужно. Коды здесь читаются запросом "19 02 FF" - "все коды с любой
 * отметкой статуса". В ответ блок отдаёт не только то, что сломано сейчас, но
 * и старые записи, и коды, тест по которым после сброса ещё не проходил. Без
 * статуса такой список выглядит как два десятка неисправностей сразу, причём с
 * взаимоисключающими парами ("низкий сигнал" и "высокий сигнал" одного
 * датчика). Статус и говорит, какие из них настоящие.
 */
object UdsStatus {
    const val TEST_FAILED = 0x01
    const val FAILED_THIS_CYCLE = 0x02
    const val PENDING = 0x04
    const val CONFIRMED = 0x08
    const val NOT_TESTED_SINCE_CLEAR = 0x10
    const val FAILED_SINCE_CLEAR = 0x20
    const val LAMP = 0x80

    /**
     * Одна фраза - самое важное из байта. Порядок проверок и есть порядок
     * важности: "есть сейчас" перекрывает "подтверждена", а та - "была когда-то".
     */
    fun describe(status: Int?): String? {
        if (status == null) return null
        val s = status and 0xFF
        val main = when {
            s and TEST_FAILED != 0 -> ObdText.pick("ошибка есть сейчас", "failing now")
            s and CONFIRMED != 0 -> ObdText.pick("подтверждена, сейчас не проявляется", "confirmed, not failing now")
            s and PENDING != 0 -> ObdText.pick("ожидает подтверждения", "pending")
            s and FAILED_THIS_CYCLE != 0 -> ObdText.pick("была в этой поездке", "failed this drive cycle")
            s and FAILED_SINCE_CLEAR != 0 -> ObdText.pick("была после последнего сброса", "failed since last clear")
            s and NOT_TESTED_SINCE_CLEAR != 0 -> ObdText.pick("тест не проходил после сброса", "not tested since last clear")
            else -> ObdText.pick("только запись в памяти", "memory record only")
        }
        val lamp = if (s and LAMP != 0) ObdText.pick(", зажигает лампу", ", lamp requested") else ""
        return "%s%s (0x%02X)".format(main, lamp, s)
    }

    /** Неисправность настоящая: есть сейчас, подтверждена или ждёт подтверждения. */
    fun isReal(status: Int?): Boolean? = status?.let { it and (TEST_FAILED or PENDING or CONFIRMED) != 0 }
}

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
     * Физический адрес блока двигателя. По UDS он отдаёт больше, чем по
     * режиму 03: например, P0500 (датчик скорости) у ME17.9.7 виден только
     * так. Сброс режимом 04 стирает и эти записи - поэтому перед сбросом их
     * перечитывают и кладут в архив.
     */
    const val ENGINE_ADDRESS = 0x7E0

    /** Подпись блока для людей: у двух адресов назначение общепринятое. */
    fun label(address: Int): String? = when (address) {
        0x7E0 -> ObdText.pick("двигатель", "engine")
        0x7E1 -> ObdText.pick("коробка передач", "transmission")
        else -> null
    }

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
            if (hi != 0 || mid != 0 || lo != 0) {
                out.add(ModuleDtc(DtcCodec.decode(hi, mid), lo, status))
            }
            i += 4
        }
        return ModuleReply.Codes(out, ModuleProtocol.UDS)
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
            if (hi != 0 || lo != 0) out.add(ModuleDtc(DtcCodec.decode(hi, lo), null, status))
            i += 3
        }
        return ModuleReply.Codes(out, ModuleProtocol.KWP)
    }
}
