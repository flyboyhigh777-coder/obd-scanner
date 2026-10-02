package com.obdscanner.core.dtc

import com.obdscanner.core.elm.ObdText

/** Откуда прочитан код: это и есть разница между режимами 03, 07 и 0A. */
enum class DtcSource(val service: Int, val ru: String, val en: String) {
    STORED(0x03, "Сохранённая", "Stored"),
    PENDING(0x07, "Ожидающая подтверждения", "Pending"),
    PERMANENT(0x0A, "Постоянная (не сбрасывается)", "Permanent");

    val title: String get() = ObdText.pick(ru, en)
}

/** Система, к которой относится код - по первой букве. */
enum class DtcSystem(val letter: Char, val ru: String, val en: String) {
    POWERTRAIN('P', "Двигатель и КПП", "Powertrain"),
    CHASSIS('C', "Шасси (тормоза, подвеска, рулевое)", "Chassis"),
    BODY('B', "Кузов и салон", "Body"),
    NETWORK('U', "Сеть и блоки управления", "Network");

    val title: String get() = ObdText.pick(ru, en)
}

/**
 * Насколько срочно. Это подсказка, а не вердикт: стандарт OBD-II степень
 * серьёзности не передаёт вообще, поэтому список "критичных" семейств задан
 * руками - там, где дальнейшая езда реально вредит мотору или катализатору.
 */
enum class DtcSeverity(val ru: String, val en: String) {
    CRITICAL("Ехать нельзя или только до сервиса", "Stop driving or drive to a garage only"),
    WARNING("Нужно разобраться", "Needs attention"),
    INFO("Можно ехать, но проверить", "Drivable, but worth checking");

    val title: String get() = ObdText.pick(ru, en)
}

/** Расшифрованный код неисправности. */
data class Dtc(val code: String, val source: DtcSource = DtcSource.STORED) {

    val system: DtcSystem = DtcSystem.entries.firstOrNull { it.letter == code.firstOrNull() }
        ?: DtcSystem.POWERTRAIN

    /**
     * Коды с 1 или 3 на второй позиции (P1xxx, P3xxx) определяет производитель,
     * с 0 и 2 - стандарт SAE. Для C/B/U производительские - это x1xxx.
     * Поэтому для "производительского" кода у нас может не быть расшифровки, и
     * это не ошибка приложения.
     */
    val manufacturerSpecific: Boolean = when (system) {
        DtcSystem.POWERTRAIN -> code.getOrNull(1) == '1' || code.getOrNull(1) == '3'
        else -> code.getOrNull(1) == '1'
    }

    val description: String get() = DtcCatalog.describe(code)

    val severity: DtcSeverity get() = DtcCatalog.severity(code)

    /** Короткое название группы ("Пропуски зажигания", "Топливо и воздух"). */
    val group: String get() = DtcCatalog.group(code)
}

/**
 * Упаковка кода неисправности в два байта и обратно (SAE J2012).
 *
 * Старшие два бита первого байта - буква (00=P, 01=C, 10=B, 11=U), следующие
 * два - первая цифра (0..3), младший полубайт - вторая цифра (hex), второй байт
 * целиком - две последние цифры в hex. Так "P0133" это байты 0x01 0x33.
 */
object DtcCodec {

    private val LETTERS = charArrayOf('P', 'C', 'B', 'U')

    fun decode(a: Int, b: Int): String {
        val letter = LETTERS[(a shr 6) and 0x03]
        val first = (a shr 4) and 0x03
        return "%c%d%X%02X".format(letter, first, a and 0x0F, b)
    }

    /** Обратное преобразование - нужно тестам и симулятору ЭБУ. */
    fun encode(code: String): Pair<Int, Int>? {
        if (code.length != 5) return null
        val letter = LETTERS.indexOf(code[0].uppercaseChar())
        if (letter < 0) return null
        val first = code[1].digitToIntOrNull() ?: return null
        if (first > 3) return null
        val second = code[2].digitToIntOrNull(16) ?: return null
        val rest = code.substring(3).toIntOrNull(16) ?: return null
        return ((letter shl 6) or (first shl 4) or second) to rest
    }

    /**
     * Список кодов из ответа на режим 03/07/0A.
     *
     * На CAN ЭБУ присылает перед кодами байт с их количеством, на K-line -
     * сразу коды, по три на строку с добиванием нулями. Отличить просто: коды
     * идут парами, поэтому нечётная длина данных означает, что первый байт -
     * счётчик. Пара 00 00 - это заполнитель, а не код P0000.
     */
    fun parse(frames: List<IntArray>, source: DtcSource = DtcSource.STORED): List<Dtc> {
        val out = LinkedHashSet<String>()
        for (frame in frames) {
            var data = frame
            if (data.size % 2 == 1) data = data.copyOfRange(1, data.size)
            var i = 0
            while (i + 1 < data.size) {
                val a = data[i]; val b = data[i + 1]
                if (a != 0 || b != 0) out.add(decode(a, b))
                i += 2
            }
        }
        return out.map { Dtc(it, source) }
    }
}
