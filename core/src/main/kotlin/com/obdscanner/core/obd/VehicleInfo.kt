package com.obdscanner.core.obd

import com.obdscanner.core.elm.ObdText

/**
 * Разбор VIN (ISO 3779) - того, что машина отдаёт на режим 09 PID 02.
 *
 * Страна определяется по первым символам однозначно (это закреплено стандартом),
 * а вот таблица производителей (WMI) принципиально неполная: кодов несколько
 * тысяч, они выдаются и отзываются, и единого открытого реестра нет. Поэтому
 * неизвестный WMI - не ошибка: показываем сам код и страну, а поле "марка"
 * остаётся пустым, вместо того чтобы угадывать.
 *
 * Год выпуска по 10-му символу тоже неоднозначен: цикл кодов - 30 лет, поэтому
 * "B" это и 1981, и 2011. Различают по 7-му символу (буква - машина 2010 года и
 * новее), и это именно соглашение североамериканского рынка, а не гарантия.
 */
data class VinInfo(
    val vin: String,
    val wmi: String,
    val country: String?,
    val manufacturer: String?,
    val modelYear: Int?,
    val serial: String,
    /** null - контрольный разряд в этом VIN не предусмотрен (не Северная Америка). */
    val checkDigitValid: Boolean?
) {
    val valid: Boolean get() = vin.length == 17
}

object VinDecoder {

    /** Буквы I, O, Q в VIN не используются - их легко спутать с 1 и 0. */
    private const val ALLOWED = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"

    /** Коды года выпуска по 10-му символу, цикл 30 лет. */
    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    /**
     * Чистит то, что пришло из шины: ЭБУ добивает ответ нулями, а на некоторых
     * машинах дописывает в начало счётчик элементов (0x01).
     */
    fun fromBytes(data: IntArray): String {
        val text = data
            .filter { it in 0x20..0x7E }
            .map { it.toChar() }
            .joinToString("")
            .trim()
        val cleaned = text.filter { it.uppercaseChar() in ALLOWED }.uppercase()
        // VIN всегда 17 символов; если налип лишний префикс - он в начале.
        return if (cleaned.length > 17) cleaned.takeLast(17) else cleaned
    }

    fun decode(vin: String): VinInfo? {
        val v = vin.trim().uppercase()
        if (v.length != 17 || v.any { it !in ALLOWED }) return null
        val wmi = v.substring(0, 3)
        return VinInfo(
            vin = v,
            wmi = wmi,
            country = country(v),
            manufacturer = MANUFACTURERS[wmi],
            modelYear = modelYear(v),
            serial = v.substring(11),
            checkDigitValid = checkDigit(v)
        )
    }

    /**
     * Год выпуска по 10-му символу.
     *
     * Код года повторяется каждые 30 лет, поэтому "G" - это и 1986, и 2016.
     * Различить помогает только соглашение рынка Северной Америки: там у машин
     * до 2010 года на 7-й позиции цифра, а с 2010-го - буква. Для остальных
     * рынков (включая Россию и Европу) такого правила нет, и честнее исходить из
     * текущего цикла: машин 1980-х на дорогах несопоставимо меньше.
     *
     * [currentYear] нужен, чтобы не выдать год из будущего: модельный год может
     * опережать календарный на один, всё что дальше - предыдущий цикл.
     */
    fun modelYear(vin: String, currentYear: Int = java.time.LocalDate.now().year): Int? {
        if (vin.length < 10) return null
        val index = YEAR_CODES.indexOf(vin[9])
        if (index < 0) return null
        val northAmerican = vin[0] in "12345"
        val oldCycle = northAmerican && vin.getOrNull(6)?.isDigit() == true
        val year = if (oldCycle) 1980 + index else 2010 + index
        return if (year > currentYear + 1) year - 30 else year
    }

    /**
     * Проверка контрольного разряда (9-й символ). Обязателен для машин
     * Северной Америки; для остальных стандарт его не требует, поэтому
     * возвращаем null - "проверка не применима", а не "VIN неверный".
     */
    fun checkDigit(vin: String): Boolean? {
        if (vin.length != 17) return null
        if (vin[0] !in "12345") return null
        val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)
        var sum = 0
        for (i in 0 until 17) {
            val value = transliterate(vin[i]) ?: return null
            sum += value * weights[i]
        }
        val expected = sum % 11
        val actual = if (vin[8] == 'X') 10 else vin[8].digitToIntOrNull() ?: return false
        return expected == actual
    }

    private fun transliterate(c: Char): Int? = when {
        c.isDigit() -> c.digitToInt()
        c in "AJ" -> 1
        c in "BKS" -> 2
        c in "CLT" -> 3
        c in "DMU" -> 4
        c in "ENV" -> 5
        c in "FW" -> 6
        c in "GPX" -> 7
        c in "HY" -> 8
        c in "RZ" -> 9
        else -> null
    }

    /** Страна сборки по первым символам VIN (ISO 3780). */
    fun country(vin: String): String? {
        if (vin.length < 2) return null
        val two = vin.substring(0, 2)
        COUNTRIES_2.entries.firstOrNull { two >= it.key.first && two <= it.key.second }?.let {
            return ObdText.pick(it.value.first, it.value.second)
        }
        val one = COUNTRIES_1[vin[0]] ?: return null
        return ObdText.pick(one.first, one.second)
    }

    /** Диапазоны из двух символов - там, где одна буква делится между странами. */
    private val COUNTRIES_2: Map<Pair<String, String>, Pair<String, String>> = linkedMapOf(
        ("SA" to "SM") to ("Великобритания" to "United Kingdom"),
        ("SN" to "ST") to ("Германия" to "Germany"),
        ("SU" to "SZ") to ("Польша" to "Poland"),
        ("TA" to "TH") to ("Швейцария" to "Switzerland"),
        ("TJ" to "TP") to ("Чехия" to "Czechia"),
        ("TR" to "TV") to ("Венгрия" to "Hungary"),
        ("TW" to "TZ") to ("Португалия" to "Portugal"),
        ("UH" to "UM") to ("Дания" to "Denmark"),
        ("UN" to "UT") to ("Ирландия" to "Ireland"),
        ("UU" to "UZ") to ("Румыния" to "Romania"),
        ("VA" to "VE") to ("Австрия" to "Austria"),
        ("VF" to "VR") to ("Франция" to "France"),
        ("VS" to "VW") to ("Испания" to "Spain"),
        ("VX" to "VZ") to ("Югославия / Сербия" to "Yugoslavia / Serbia"),
        ("XA" to "XE") to ("Россия" to "Russia"),
        ("XF" to "XK") to ("Россия" to "Russia"),
        ("XL" to "XR") to ("Нидерланды" to "Netherlands"),
        ("XS" to "XW") to ("Россия / СССР" to "Russia / USSR"),
        ("X0" to "X3") to ("Россия" to "Russia"),
        ("X4" to "X9") to ("Россия" to "Russia"),
        ("YA" to "YE") to ("Бельгия" to "Belgium"),
        ("YF" to "YK") to ("Финляндия" to "Finland"),
        ("YS" to "YW") to ("Швеция" to "Sweden"),
        ("ZA" to "ZR") to ("Италия" to "Italy"),
        ("Z6" to "Z9") to ("Россия" to "Russia")
    )

    private val COUNTRIES_1: Map<Char, Pair<String, String>> = mapOf(
        '1' to ("США" to "United States"),
        '4' to ("США" to "United States"),
        '5' to ("США" to "United States"),
        '2' to ("Канада" to "Canada"),
        '3' to ("Мексика" to "Mexico"),
        '6' to ("Австралия" to "Australia"),
        '7' to ("Новая Зеландия" to "New Zealand"),
        '8' to ("Аргентина / Чили" to "Argentina / Chile"),
        '9' to ("Бразилия" to "Brazil"),
        'J' to ("Япония" to "Japan"),
        'K' to ("Южная Корея" to "South Korea"),
        'L' to ("Китай" to "China"),
        'M' to ("Индия / Индонезия / Таиланд" to "India / Indonesia / Thailand"),
        'N' to ("Турция / Иран" to "Turkey / Iran"),
        'P' to ("Малайзия / Филиппины" to "Malaysia / Philippines"),
        'R' to ("Тайвань / Вьетнам / ОАЭ" to "Taiwan / Vietnam / UAE"),
        'S' to ("Великобритания" to "United Kingdom"),
        'T' to ("Швейцария" to "Switzerland"),
        'U' to ("Румыния" to "Romania"),
        'V' to ("Франция" to "France"),
        'W' to ("Германия" to "Germany"),
        'X' to ("Россия" to "Russia"),
        'Y' to ("Швеция / Финляндия" to "Sweden / Finland"),
        'Z' to ("Италия" to "Italy")
    )

    /**
     * Производители по WMI. Список заведомо неполный - только то, что реально
     * встречается на дорогах; для остальных приложение покажет сам WMI.
     */
    private val MANUFACTURERS: Map<String, String> = mapOf(
        "WVW" to "Volkswagen", "WV1" to "Volkswagen (коммерческие)", "WV2" to "Volkswagen (коммерческие)",
        "WAU" to "Audi", "WA1" to "Audi SUV", "TRU" to "Audi (Венгрия)", "WUA" to "Audi Sport",
        "WBA" to "BMW", "WBS" to "BMW M", "WBY" to "BMW i", "X4X" to "BMW (Калининград)",
        "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "WDC" to "Mercedes-Benz SUV",
        "WME" to "Smart", "WMW" to "MINI", "WP0" to "Porsche", "WP1" to "Porsche SUV",
        "WF0" to "Ford (Европа)", "WMA" to "MAN", "W0L" to "Opel", "W0V" to "Opel",
        "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroen", "VF6" to "Renault Trucks",
        "VSS" to "SEAT", "TMB" to "Skoda", "TMP" to "Skoda",
        "ZFA" to "Fiat", "ZFF" to "Ferrari", "ZAR" to "Alfa Romeo", "ZAM" to "Maserati",
        "ZLA" to "Lancia", "ZHW" to "Lamborghini",
        "SAL" to "Land Rover", "SAJ" to "Jaguar", "SCB" to "Bentley", "SCF" to "Aston Martin",
        "SCC" to "Lotus", "SHH" to "Honda (Великобритания)", "SJN" to "Nissan (Великобритания)",
        "YV1" to "Volvo", "YV4" to "Volvo", "YS3" to "Saab",
        "JHM" to "Honda", "JHL" to "Honda", "JF1" to "Subaru", "JF2" to "Subaru",
        "JM1" to "Mazda", "JMZ" to "Mazda", "JN1" to "Nissan", "JN8" to "Nissan",
        "JT2" to "Toyota", "JT3" to "Toyota", "JTD" to "Toyota", "JTE" to "Toyota",
        "JTH" to "Lexus", "JTJ" to "Lexus", "JA3" to "Mitsubishi", "JA4" to "Mitsubishi",
        "JS2" to "Suzuki", "JS3" to "Suzuki",
        "KMH" to "Hyundai", "KMJ" to "Hyundai", "KNA" to "Kia", "KND" to "Kia", "KNE" to "Kia",
        "KL1" to "Chevrolet (Korea)", "KLA" to "Daewoo",
        "LFV" to "FAW-Volkswagen", "LSV" to "SAIC Volkswagen", "LVS" to "Ford (Китай)",
        "LGW" to "Great Wall / Haval", "LVV" to "Chery", "LB3" to "Geely", "LJD" to "Dongfeng",
        "L6T" to "Geely", "LZW" to "SAIC-GM-Wuling",
        "XTA" to "LADA (АвтоВАЗ)", "XTT" to "УАЗ", "XTC" to "КамАЗ", "XTH" to "ГАЗ",
        "XW8" to "Volkswagen (Калуга)", "X7L" to "Renault (Москва)", "Z94" to "Hyundai (Санкт-Петербург)",
        "Z8N" to "Nissan (Санкт-Петербург)", "Z6F" to "Ford (Елабуга)",
        "1G1" to "Chevrolet", "1G4" to "Buick", "1GC" to "Chevrolet (грузовые)",
        "1FA" to "Ford", "1FM" to "Ford SUV", "1FT" to "Ford (грузовые)",
        "1C3" to "Chrysler", "1C4" to "Chrysler SUV", "1J4" to "Jeep",
        "1N4" to "Nissan (США)", "1HG" to "Honda (США)", "2HG" to "Honda (Канада)",
        "3VW" to "Volkswagen (Мексика)", "4T1" to "Toyota (США)", "4T3" to "Toyota (США)",
        "5NP" to "Hyundai (США)", "5YJ" to "Tesla", "7SA" to "Tesla",
        "9BW" to "Volkswagen (Бразилия)", "93H" to "Honda (Бразилия)",
        "MA3" to "Suzuki (Индия)", "MAT" to "Tata", "MA1" to "Mahindra",
        "MR0" to "Toyota (Таиланд)", "MNT" to "Nissan (Таиланд)", "NMT" to "Toyota (Турция)"
    )
}
