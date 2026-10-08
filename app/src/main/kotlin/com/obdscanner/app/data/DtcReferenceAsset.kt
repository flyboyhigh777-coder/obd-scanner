package com.obdscanner.app.data

import android.content.Context
import com.obdscanner.core.dtc.DtcCatalog
import com.obdscanner.core.dtc.DtcReference
import com.obdscanner.core.elm.ObdText

/**
 * Полный справочник обобщённых кодов из ресурсов приложения: 9 533 кода с
 * русским и английским названием, частыми причинами и симптомами.
 *
 * Два файла (см. data/SOURCES.md):
 *
 *  * `assets/dtc_ru.tsv` - по строке на код:
 *    "P0301<таб>Пропуски зажигания в цил. 1<таб>Cylinder 1 Misfire
 *    Detected<таб>513,514,515<таб>510,511,127";
 *  * `assets/dtc_ru_texts.tsv` - "номер<таб>по-русски<таб>по-английски".
 *
 * Причины и симптомы вынесены в отдельный файл и хранятся номерами, потому что
 * фраза "Горит Check Engine" относится к тысячам кодов: 4 889 фраз вместо
 * примерно 48 тысяч повторов - это в десять раз меньше и текста в сборке, и
 * строк в памяти.
 *
 * Два мегабайта текста читаются один раз в фоне и остаются в памяти - это
 * дешевле, чем каждый раз искать строку в файле, и заметно дешевле, чем тащить
 * в приложение базу данных ради одной таблицы "ключ-значение".
 *
 * Разбор ручной, без JSON и без библиотек: построчный формат не стоит ни
 * зависимости, ни времени на разбор 14 тысяч объектов.
 */
object DtcReferenceAsset {

    private const val CODES = "dtc_ru.tsv"
    private const val TEXTS = "dtc_ru_texts.tsv"

    @Volatile
    private var codes: Map<String, DtcReference>? = null

    val loaded: Boolean get() = codes != null

    val size: Int get() = codes?.size ?: 0

    /**
     * Читает справочник и подключает его к [DtcCatalog]. Вызывать из фонового
     * потока: это чтение файла.
     */
    fun load(context: Context) {
        if (codes != null) return
        val parsed = runCatching {
            val texts = readTexts(context)
            val map = HashMap<String, DtcReference>(12_000)
            context.assets.open(CODES).bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val f = line.split('\t')
                    if (f.size < 3 || f[0].isEmpty()) continue
                    map[f[0]] = DtcReference(
                        ru = f[1],
                        en = f[2],
                        causes = phrases(f.getOrNull(3), texts),
                        symptoms = phrases(f.getOrNull(4), texts),
                    )
                }
            }
            map
        }.getOrNull() ?: return
        codes = parsed
        DtcCatalog.reference = { code -> parsed[code] }
    }

    /**
     * Номер в файле явный, а не "по порядку строк": иначе любой комментарий
     * или пустая строка сдвинули бы все ссылки на фразы.
     */
    private fun readTexts(context: Context): Map<Int, Pair<String, String>> {
        val out = HashMap<Int, Pair<String, String>>(6_000)
        context.assets.open(TEXTS).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val f = line.split('\t')
                if (f.size < 3) continue
                val index = f[0].toIntOrNull() ?: continue
                out[index] = f[1] to f[2]
            }
        }
        return out
    }

    private fun phrases(field: String?, texts: Map<Int, Pair<String, String>>): List<String> {
        if (field.isNullOrEmpty()) return emptyList()
        return field.split(',').mapNotNull { part ->
            val pair = texts[part.toIntOrNull() ?: return@mapNotNull null] ?: return@mapNotNull null
            // Язык выбирается при чтении: справочник переживает смену локали
            // только вместе с процессом, а это и так перезапуск приложения.
            ObdText.pick(pair.first, pair.second)
        }
    }
}
