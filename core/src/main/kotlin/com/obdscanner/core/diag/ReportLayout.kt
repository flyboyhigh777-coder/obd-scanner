package com.obdscanner.core.diag

/** Роль куска текста на странице - от неё зависят размер, начертание и цвет. */
enum class ReportTextRole { TITLE, SUBTITLE, SECTION, LABEL, VALUE, NOTE, FOOTER }

/** Текст, который нужно нарисовать: что, где и в какой роли. */
data class ReportDraw(
    val text: String,
    val x: Float,
    /** Базовая линия текста от верха страницы. */
    val baseline: Float,
    val role: ReportTextRole,
    val tone: ReportTone = ReportTone.PLAIN
)

/** Горизонтальная линейка под заголовком раздела. */
data class ReportRule(val y: Float, val left: Float, val right: Float)

data class ReportPage(val number: Int, val texts: List<ReportDraw>, val rules: List<ReportRule>)

/** Размеры страницы в точках. A4 при 72 dpi - 595 x 842. */
data class ReportGeometry(
    val width: Float = 595f,
    val height: Float = 842f,
    val marginLeft: Float = 40f,
    val marginRight: Float = 40f,
    val marginTop: Float = 48f,
    val marginBottom: Float = 52f
) {
    val contentWidth: Float get() = width - marginLeft - marginRight
    val bottom: Float get() = height - marginBottom
}

/**
 * Раскладка отчёта по страницам: что и где рисовать.
 *
 * Вынесена из рисования сознательно. Рисование - это десяток вызовов
 * `drawText`, в них ошибиться негде; ошибаются в другом - в переносах и в
 * разрывах страниц, и ошибка выглядит как потерянный хвост отчёта или текст,
 * уехавший за край листа. Здесь это обычный Kotlin без единого обращения к
 * Android, поэтому проверяется обычными тестами, а не на телефоне.
 *
 * [measure] - ширина строки в точках; её считает тот, кто умеет (на Android -
 * `Paint.measureText`), а в тестах - простая функция.
 */
class ReportLayout(
    private val geometry: ReportGeometry = ReportGeometry(),
    private val footer: String = "",
    private val measure: (text: String, size: Float, bold: Boolean) -> Float
) {

    /** Высота шрифта для каждой роли. */
    fun size(role: ReportTextRole): Float = when (role) {
        ReportTextRole.TITLE -> 18f
        ReportTextRole.SUBTITLE -> 10f
        ReportTextRole.SECTION -> 13f
        ReportTextRole.LABEL -> 11f
        ReportTextRole.VALUE -> 11f
        ReportTextRole.NOTE -> 10f
        ReportTextRole.FOOTER -> 8f
    }

    fun isBold(role: ReportTextRole): Boolean =
        role == ReportTextRole.TITLE || role == ReportTextRole.SECTION || role == ReportTextRole.VALUE

    private val noteIndent = 14f

    fun paginate(blocks: List<ReportBlock>): List<ReportPage> {
        val pages = ArrayList<ReportPage>()
        var texts = ArrayList<ReportDraw>()
        var rules = ArrayList<ReportRule>()
        var y = geometry.marginTop

        fun flush() {
            if (footer.isNotEmpty()) {
                texts.add(
                    ReportDraw(
                        "$footer · ${pages.size + 1}",
                        geometry.marginLeft,
                        geometry.height - geometry.marginBottom + 28f,
                        ReportTextRole.FOOTER
                    )
                )
            }
            pages.add(ReportPage(pages.size + 1, texts, rules))
            texts = ArrayList()
            rules = ArrayList()
            y = geometry.marginTop
        }

        /** Хватит ли места под строку высотой [height]; если нет - новая страница. */
        fun ensure(height: Float) {
            if (y + height > geometry.bottom) flush()
        }

        fun lineHeight(role: ReportTextRole): Float = size(role) + if (role == ReportTextRole.NOTE) 6f else 7f

        /** Текст с переносом по словам, а длинное слово - по символам. */
        fun wrapped(text: String, role: ReportTextRole, left: Float, tone: ReportTone) {
            val size = size(role)
            val bold = isBold(role)
            val maxWidth = geometry.width - geometry.marginRight - left
            val height = lineHeight(role)

            fun put(line: String) {
                ensure(height)
                texts.add(ReportDraw(line, left, y + size, role, tone))
                y += height
            }

            var line = ""
            for (word in text.split(' ').filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (measure(candidate, size, bold) <= maxWidth) {
                    line = candidate
                    continue
                }
                if (line.isNotEmpty()) put(line)
                // Слово длиннее строки (длинный код калибровки) режется по
                // символам: иначе оно уедет за правое поле.
                var rest = word
                while (measure(rest, size, bold) > maxWidth && rest.length > 1) {
                    var cut = rest.length
                    while (cut > 1 && measure(rest.substring(0, cut), size, bold) > maxWidth) cut--
                    put(rest.substring(0, cut))
                    rest = rest.substring(cut)
                }
                line = rest
            }
            if (line.isNotEmpty()) put(line)
        }

        for (block in blocks) {
            when (block) {
                is ReportBlock.Title -> {
                    ensure(34f)
                    val size = size(ReportTextRole.TITLE)
                    texts.add(ReportDraw(block.text, geometry.marginLeft, y + size, ReportTextRole.TITLE))
                    y += size + 8f
                    if (block.subtitle.isNotEmpty()) {
                        val subSize = size(ReportTextRole.SUBTITLE)
                        texts.add(
                            ReportDraw(block.subtitle, geometry.marginLeft, y + subSize, ReportTextRole.SUBTITLE)
                        )
                        y += subSize + 6f
                    }
                    rules.add(ReportRule(y, geometry.marginLeft, geometry.width - geometry.marginRight))
                    y += 10f
                }

                is ReportBlock.Section -> {
                    // Заголовок раздела в конце страницы без единой строки под
                    // ним - висячая строка: читатель видит название и обрыв.
                    ensure(30f + lineHeight(ReportTextRole.LABEL))
                    val size = size(ReportTextRole.SECTION)
                    texts.add(ReportDraw(block.text, geometry.marginLeft, y + size, ReportTextRole.SECTION))
                    y += size + 5f
                    rules.add(ReportRule(y, geometry.marginLeft, geometry.width - geometry.marginRight))
                    y += 9f
                }

                is ReportBlock.Row -> {
                    val labelSize = size(ReportTextRole.LABEL)
                    val valueSize = size(ReportTextRole.VALUE)
                    val labelWidth = measure(block.label, labelSize, false)
                    val valueWidth = measure(block.value, valueSize, true)
                    val height = lineHeight(ReportTextRole.LABEL)
                    if (labelWidth + valueWidth + 16f <= geometry.contentWidth) {
                        // Название слева, значение прижато к правому краю.
                        ensure(height)
                        texts.add(
                            ReportDraw(block.label, geometry.marginLeft, y + labelSize, ReportTextRole.LABEL)
                        )
                        texts.add(
                            ReportDraw(
                                block.value,
                                geometry.width - geometry.marginRight - valueWidth,
                                y + valueSize,
                                ReportTextRole.VALUE,
                                block.tone
                            )
                        )
                        y += height
                    } else {
                        wrapped(block.label, ReportTextRole.LABEL, geometry.marginLeft, ReportTone.PLAIN)
                        wrapped(block.value, ReportTextRole.VALUE, geometry.marginLeft + noteIndent, block.tone)
                    }
                }

                is ReportBlock.Note ->
                    wrapped(block.text, ReportTextRole.NOTE, geometry.marginLeft + noteIndent, block.tone)

                ReportBlock.Space -> y += 8f
            }
        }
        if (texts.isNotEmpty()) flush()
        return pages
    }
}
