package com.obdscanner.core.monitor

import com.obdscanner.core.elm.ObdText

/**
 * Единица измерения и масштаб из таблицы "unit and scaling identifiers" (UASID)
 * стандарта SAE J1979.
 *
 * Эта таблица нужна ровно одному режиму - 06 (результаты бортовых тестов). Там
 * ЭБУ присылает не "температуру" и не "напряжение", а три числа без имени:
 * результат теста, нижний предел и верхний. Чем эти числа являются, говорит
 * один байт - идентификатор из этой таблицы. Без него результат нельзя ни
 * показать, ни сравнить с пределами: `0x0190` - это и 400 отсчётов, и 4,00 В, и
 * 16,0 °C, смотря какой идентификатор пришёл.
 *
 * Отсюда же берётся [signed]: часть идентификаторов означает величину со знаком
 * (коррекция смеси бывает отрицательной), и тогда два байта читаются как
 * дополнительный код, а не как беззнаковое число.
 */
data class UnitScaling(
    val id: Int,
    val scale: Double,
    val offset: Double,
    val unitRu: String,
    val unitEn: String,
    val decimals: Int,
    val signed: Boolean = false
) {
    val unit: String get() = ObdText.pick(unitRu, unitEn)

    /** Физическая величина из сырых отсчётов. */
    fun value(raw: Int): Double = raw * scale + offset
}

/**
 * Таблица UASID. Заполнена теми идентификаторами, которые стандарт описывает
 * однозначно; остальные (в том числе весь диапазон, отданный производителю)
 * возвращают null, и тогда приложение показывает сырые отсчёты и честно пишет,
 * что масштаб неизвестен. Выдумывать множитель здесь нельзя: ошибка в нём
 * превращает пройденный тест в проваленный.
 */
object UnitScalings {

    private fun u(
        id: Int,
        scale: Double,
        unitRu: String,
        unitEn: String,
        decimals: Int,
        offset: Double = 0.0,
        signed: Boolean = false
    ) = UnitScaling(id, scale, offset, unitRu, unitEn, decimals, signed)

    private const val NO_UNIT = ""

    private val TABLE: Map<Int, UnitScaling> = listOf(
        // Безразмерные отсчёты.
        u(0x01, 1.0, NO_UNIT, NO_UNIT, 0),
        u(0x02, 0.1, NO_UNIT, NO_UNIT, 1),
        u(0x03, 0.01, NO_UNIT, NO_UNIT, 2),
        u(0x04, 0.001, NO_UNIT, NO_UNIT, 3),
        u(0x05, 0.0000305, NO_UNIT, NO_UNIT, 5),
        u(0x06, 0.000305, NO_UNIT, NO_UNIT, 4),
        // Частота вращения, скорость.
        u(0x07, 0.25, "об/мин", "rpm", 0),
        u(0x08, 0.01, "км/ч", "km/h", 2),
        u(0x09, 1.0, "км/ч", "km/h", 0),
        // Напряжение и ток.
        u(0x0A, 0.122, "мВ", "mV", 1),
        u(0x0B, 0.001, "В", "V", 3),
        u(0x0C, 0.01, "В", "V", 2),
        u(0x0D, 0.00390625, "мА", "mA", 3),
        u(0x0E, 0.001, "А", "A", 3),
        u(0x0F, 0.01, "А", "A", 2),
        // Время.
        u(0x10, 1.0, "мс", "ms", 0),
        u(0x11, 0.1, "с", "s", 1),
        u(0x12, 1.0, "с", "s", 0),
        // Сопротивление.
        u(0x13, 1.0, "мОм", "mOhm", 0),
        u(0x14, 1.0, "Ом", "Ohm", 0),
        u(0x15, 1.0, "кОм", "kOhm", 0),
        // Температура: единственная в таблице со сдвигом.
        u(0x16, 0.1, "°C", "°C", 1, offset = -40.0),
        // Давление. Множители разные, потому что разные датчики: во впуске,
        // атмосферное, в топливной рейке, в дизельной рейке.
        u(0x17, 0.01, "кПа", "kPa", 2),
        u(0x18, 0.0117, "кПа", "kPa", 3),
        u(0x19, 0.079, "кПа", "kPa", 2),
        u(0x1A, 1.0, "кПа", "kPa", 0),
        u(0x1B, 10.0, "кПа", "kPa", 0),
        // Угол.
        u(0x1C, 0.01, "°", "°", 2),
        u(0x1D, 0.5, "°", "°", 1),
        // Состав смеси.
        u(0x1E, 0.0000305, NO_UNIT, NO_UNIT, 4),
        u(0x1F, 0.05, NO_UNIT, NO_UNIT, 2),
        u(0x20, 0.00390625, NO_UNIT, NO_UNIT, 4),
        // Частота.
        u(0x21, 1.0, "мГц", "mHz", 0),
        u(0x22, 1.0, "Гц", "Hz", 0),
        u(0x23, 1.0, "кГц", "kHz", 0),
        // Счётчики и пробег.
        u(0x24, 1.0, NO_UNIT, NO_UNIT, 0),
        u(0x25, 1.0, "км", "km", 0),
        // Производные величины.
        u(0x26, 0.1, "В/мс", "V/ms", 1),
        u(0x27, 0.01, "г/с", "g/s", 2),
        u(0x28, 1.0, "г/с", "g/s", 0),
        u(0x29, 0.25, "кПа/с", "kPa/s", 2),
        u(0x2A, 0.001, "кг/ч", "kg/h", 3),
        u(0x2B, 1.0, NO_UNIT, NO_UNIT, 0),
        u(0x2C, 0.01, "г/цил", "g/cyl", 2),
        u(0x2D, 0.01, "мг/такт", "mg/stroke", 2),
        u(0x2E, 1.0, NO_UNIT, NO_UNIT, 0),
        // Проценты.
        u(0x2F, 0.01, "%", "%", 2),
        u(0x30, 0.001526, "%", "%", 3),
        u(0x31, 0.001, "л", "L", 3),
        u(0x33, 0.00024414, NO_UNIT, NO_UNIT, 5),
        u(0x34, 1.0, "мин", "min", 0),
        u(0x35, 10.0, "с", "s", 0),

        // Те же величины, но со знаком: 0x81.. повторяют 0x01.. .
        u(0x81, 1.0, NO_UNIT, NO_UNIT, 0, signed = true),
        u(0x82, 0.1, NO_UNIT, NO_UNIT, 1, signed = true),
        u(0x83, 0.01, NO_UNIT, NO_UNIT, 2, signed = true),
        u(0x84, 0.001, NO_UNIT, NO_UNIT, 3, signed = true),
        u(0x85, 0.0000305, NO_UNIT, NO_UNIT, 5, signed = true),
        u(0x86, 0.000305, NO_UNIT, NO_UNIT, 4, signed = true),
        u(0x8A, 0.122, "мВ", "mV", 1, signed = true),
        u(0x8B, 0.001, "В", "V", 3, signed = true),
        u(0x8C, 0.01, "В", "V", 2, signed = true),
        u(0x8D, 0.00390625, "мА", "mA", 3, signed = true),
        u(0x8E, 0.001, "А", "A", 3, signed = true),
        u(0x90, 1.0, "мс", "ms", 0, signed = true),
        u(0x96, 0.1, "°C", "°C", 1, signed = true),
        u(0x9C, 0.01, "°", "°", 2, signed = true),
        u(0x9D, 0.5, "°", "°", 1, signed = true),
        u(0xA8, 1.0, NO_UNIT, NO_UNIT, 0, signed = true),
        u(0xA9, 1.0, "км", "km", 0, signed = true),
        u(0xAF, 0.01, "%", "%", 2, signed = true),
        u(0xB0, 0.003052, "%", "%", 3, signed = true),
        u(0xB1, 0.001, "л", "L", 3, signed = true),
        u(0xFD, 0.001, "кПа", "kPa", 3, signed = true),
        u(0xFE, 0.25, "Па", "Pa", 2, signed = true)
    ).associateBy { it.id }

    /** null - такого идентификатора в стандарте нет либо он отдан производителю. */
    fun of(id: Int): UnitScaling? = TABLE[id]

    /** Идентификатор 0x2E - это не число, а "да/нет". */
    fun isBoolean(id: Int): Boolean = id == 0x2E

    /** Отдан ли идентификатор производителю (0x36..0x80, 0xB2..0xFC и прочие дыры). */
    fun isManufacturerRange(id: Int): Boolean = of(id) == null && !isBoolean(id)
}
