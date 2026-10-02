package com.obdscanner.core.dtc

import com.obdscanner.core.elm.ObdText

/** Семейства блоков управления, для которых есть таблица кодов производителя. */
object ManufacturerDtcFamily {
    /** Bosch ME17.9.7 - ЗМЗ-409 (УАЗ Патриот, Хантер), двигатели ГАЗ. */
    const val ME17_9_7 = "ME17.9.7"
}

/**
 * Таблица кодов производителя для одного семейства блоков управления.
 *
 * [sourceRu] печатается рядом с расшифровкой в приложении - чтобы было видно,
 * что это не стандарт SAE, а сводка по конкретному контроллеру, и её стоит
 * сверять с документацией.
 */
data class ManufacturerDtcTable(
    val family: String,
    val sourceRu: String,
    val sourceEn: String,
    val codes: Map<String, Pair<String, String>>
) {
    val source: String get() = ObdText.pick(sourceRu, sourceEn)

    fun describe(code: String): String? =
        codes[code.uppercase()]?.let { ObdText.pick(it.first, it.second) }
}

/**
 * Коды производителя.
 *
 * Стандарт SAE J2012 оставляет диапазоны P1xxx, P3xxx (и x1xxx у C/B/U) на
 * усмотрение производителя, поэтому ни один обобщённый справочник их не
 * содержит - в том числе и датасет, из которого собран `dtc_sae_en.tsv`. Для
 * машины на ЗМЗ-409 это как раз самые частые коды: иммобилизатор, регулятор
 * холостого хода, реле бензонасоса, датчик неровной дороги.
 *
 * Таблица собрана по открытым описаниям контроллеров Bosch M7.9.7/ME17.9.7
 * (ВАЗ/ГАЗ/УАЗ) и ЗАВЕДОМО НЕПОЛНАЯ: прошивки у разных лет и комплектаций
 * различаются. Поэтому код, которого здесь нет, приложение честно показывает
 * как "код производителя без расшифровки", а не подставляет похожий.
 */
object ManufacturerDtc {

    val ME17_9_7 = ManufacturerDtcTable(
        family = ManufacturerDtcFamily.ME17_9_7,
        sourceRu = "Сводка по Bosch ME17.9.7 (ЗМЗ-409): не стандарт SAE, сверяйте с документацией",
        sourceEn = "Bosch ME17.9.7 (ZMZ-409) summary: not an SAE standard, verify against the manual",
        codes = mapOf(
            "P1101" to ("Показания датчика массового расхода воздуха вне допустимого диапазона" to
                "Mass air flow sensor out of range"),
            "P1102" to ("Низкое сопротивление нагревателя датчика кислорода" to
                "Oxygen sensor heater resistance too low"),
            "P1115" to ("Неисправность цепи нагревателя датчика кислорода" to
                "Oxygen sensor heater circuit malfunction"),
            "P1123" to ("Топливоподача: богатая смесь на холостом ходу" to
                "Fuel system too rich at idle"),
            "P1124" to ("Топливоподача: бедная смесь на холостом ходу" to
                "Fuel system too lean at idle"),
            "P1127" to ("Топливоподача: богатая смесь на частичных нагрузках" to
                "Fuel system too rich at part load"),
            "P1128" to ("Топливоподача: бедная смесь на частичных нагрузках" to
                "Fuel system too lean at part load"),
            "P1135" to ("Цепь нагревателя датчика кислорода 1: обрыв или замыкание" to
                "Oxygen sensor 1 heater circuit open or shorted"),
            "P1140" to ("Измеренная нагрузка не соответствует расчётной (подсос воздуха, ДМРВ)" to
                "Measured load does not match the calculated load"),
            "P1171" to ("Низкий сигнал датчика кислорода при проверке топливоподачи под нагрузкой" to
                "Low oxygen sensor signal during the power-enrichment fuel test"),
            "P1301" to ("Пропуски воспламенения в цилиндре 1, опасные для катализатора" to
                "Cylinder 1 misfire, catalyst-damaging"),
            "P1302" to ("Пропуски воспламенения в цилиндре 2, опасные для катализатора" to
                "Cylinder 2 misfire, catalyst-damaging"),
            "P1303" to ("Пропуски воспламенения в цилиндре 3, опасные для катализатора" to
                "Cylinder 3 misfire, catalyst-damaging"),
            "P1304" to ("Пропуски воспламенения в цилиндре 4, опасные для катализатора" to
                "Cylinder 4 misfire, catalyst-damaging"),
            "P1336" to ("Рассогласование сигналов датчиков коленвала и распредвала" to
                "Crankshaft / camshaft signal mismatch"),
            "P1386" to ("Ошибка теста канала датчика детонации" to
                "Knock sensor channel test error"),
            "P1410" to ("Цепь клапана продувки адсорбера: замыкание на бортсеть" to
                "Canister purge valve circuit shorted to battery"),
            "P1425" to ("Цепь клапана продувки адсорбера: замыкание на массу" to
                "Canister purge valve circuit shorted to ground"),
            "P1426" to ("Цепь клапана продувки адсорбера: обрыв" to
                "Canister purge valve circuit open"),
            "P1500" to ("Цепь управления реле бензонасоса: обрыв или замыкание" to
                "Fuel pump relay control circuit open or shorted"),
            "P1501" to ("Цепь управления реле бензонасоса: замыкание на массу" to
                "Fuel pump relay control circuit shorted to ground"),
            "P1502" to ("Цепь управления реле бензонасоса: замыкание на бортсеть" to
                "Fuel pump relay control circuit shorted to battery"),
            "P1509" to ("Перегрузка цепи управления регулятором холостого хода" to
                "Idle air control valve circuit overload"),
            "P1513" to ("Цепь регулятора холостого хода: замыкание на массу" to
                "Idle air control valve circuit shorted to ground"),
            "P1514" to ("Цепь регулятора холостого хода: обрыв" to
                "Idle air control valve circuit open"),
            "P1541" to ("Цепь управления реле бензонасоса: обрыв" to
                "Fuel pump relay control circuit open"),
            "P1570" to ("Иммобилизатор: нет ответа или ответ не принят" to
                "Immobiliser: no answer or answer rejected"),
            "P1600" to ("Нет связи с иммобилизатором" to
                "No communication with the immobiliser"),
            "P1602" to ("Пропадание напряжения питания блока управления" to
                "Control module supply voltage dropout"),
            "P1606" to ("Датчик неровной дороги: неверный сигнал" to
                "Rough road sensor: invalid signal"),
            "P1612" to ("Ошибка сброса блока управления" to
                "Control module reset error"),
            "P1616" to ("Датчик неровной дороги: низкий уровень сигнала" to
                "Rough road sensor: signal too low"),
            "P1617" to ("Датчик неровной дороги: высокий уровень сигнала" to
                "Rough road sensor: signal too high"),
            "P1620" to ("Ошибка ПЗУ блока управления" to "Control module ROM error"),
            "P1621" to ("Ошибка ОЗУ блока управления" to "Control module RAM error"),
            "P1622" to ("Ошибка энергонезависимой памяти (EEPROM)" to "EEPROM error"),
            "P1640" to ("Ошибка теста записи и чтения EEPROM" to "EEPROM read/write test error"),
            "P1689" to ("Недопустимый код в таблице неисправностей" to "Invalid code in the fault table")
        )
    )

    private val TABLES: Map<String, ManufacturerDtcTable> = mapOf(ME17_9_7.family to ME17_9_7)

    fun table(family: String?): ManufacturerDtcTable? = family?.let { TABLES[it] }

    /** Число кодов в таблице - показывается на экране "Авто". */
    fun size(family: String?): Int = table(family)?.codes?.size ?: 0
}
