package com.obdscanner.app.data

import android.content.Context
import com.obdscanner.core.dtc.DtcCatalog

/**
 * Полный справочник обобщённых кодов SAE из ресурсов приложения.
 *
 * Файл `assets/dtc_sae_en.tsv` - это 9 533 кода из открытого датасета obdex
 * (CC0, см. data/SOURCES.md), по строке на код: "P0301<таб>Cylinder 1 Misfire
 * Detected". Он нужен третьим слоем поиска: свой русский справочник описывает
 * несколько сотен самых частых кодов, таблица ЭБУ - коды производителя, а всё
 * остальное до этого показывалось только описанием семейства.
 *
 * Полмегабайта текста читаются один раз в фоне и остаются в памяти картой
 * (около 1.5 МБ) - это дешевле, чем каждый раз искать строку в файле, и
 * заметно дешевле, чем тащить в приложение базу данных ради одной таблицы
 * "ключ-значение".
 *
 * Разбор ручной, без JSON и без библиотек: формат из двух полей не стоит ни
 * зависимости, ни времени на разбор 9.5 тысяч объектов.
 */
object SaeDtcCatalogAsset {

    private const val ASSET = "dtc_sae_en.tsv"

    @Volatile
    private var codes: Map<String, String>? = null

    val loaded: Boolean get() = codes != null

    val size: Int get() = codes?.size ?: 0

    /**
     * Читает справочник и подключает его к [DtcCatalog]. Вызывать из фонового
     * потока: это чтение файла.
     */
    fun load(context: Context) {
        if (codes != null) return
        val parsed = runCatching {
            val map = HashMap<String, String>(12_000)
            context.assets.open(ASSET).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    map[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
            map
        }.getOrNull() ?: return
        codes = parsed
        DtcCatalog.saeFallback = { code -> parsed[code] }
    }
}
