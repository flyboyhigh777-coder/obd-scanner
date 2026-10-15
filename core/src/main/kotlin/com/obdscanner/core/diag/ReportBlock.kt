package com.obdscanner.core.diag

/**
 * Насколько строка отчёта тревожная. Текстовому виду это безразлично, а в PDF
 * - цвет: в документе, который смотрят пять секунд, красная строка находится
 * взглядом, а не чтением.
 */
enum class ReportTone { PLAIN, GOOD, WARN, BAD }

/**
 * Кусок отчёта о диагностике.
 *
 * Отчёт существует в одном виде и показывается в двух - текстом и страницами
 * PDF. Собирать его дважды, по разу на формат, значит однажды поправить одно и
 * забыть другое: в PDF появится то, чего нет в тексте, и наоборот.
 */
sealed interface ReportBlock {

    /** Шапка: название отчёта и строка под ним (дата и VIN). */
    data class Title(val text: String, val subtitle: String = "") : ReportBlock

    /** Заголовок раздела. */
    data class Section(val text: String) : ReportBlock

    /** Строка "название - значение". */
    data class Row(
        val label: String,
        val value: String,
        val tone: ReportTone = ReportTone.PLAIN
    ) : ReportBlock

    /** Строка с отступом: код с расшифровкой, пределы теста, пояснение. */
    data class Note(val text: String, val tone: ReportTone = ReportTone.PLAIN) : ReportBlock

    /** Пустая строка между разделами. */
    data object Space : ReportBlock
}
