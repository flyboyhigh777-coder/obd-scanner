package com.obdscanner.core.obd

/**
 * Разбор и вычисление формулы пересчёта байт ответа в физическую величину -
 * того самого выражения, которое в описаниях PID выглядит как
 * `(256*A + B) / 4` или `A/2 - 64`.
 *
 * Нужно для пользовательских PID: стандарт описывает около полутора сотен
 * параметров, а каждый производитель добавляет свои, и единственный способ
 * позволить человеку добавить "обороты вентилятора" из форума по своей машине -
 * дать ему вписать номер PID и формулу.
 *
 * Поддерживается: числа, переменные A..H (байты ответа по порядку), + - * /,
 * скобки и унарный минус. Этого хватает на все известные формулы режимов 01 и
 * 22; функций вроде min/max в них не бывает, и добавлять их - значит заводить
 * синтаксис, который потом некому проверить.
 *
 * Разбор - рекурсивный спуск: грамматика маленькая, а ошибки нужно показывать
 * человеку понятным текстом, а не падением.
 */
class Formula private constructor(private val root: Node, val text: String) {

    /** Вычисляет значение. null - если в ответе меньше байт, чем просит формула. */
    fun evaluate(bytes: IntArray): Double? {
        val value = root.eval(bytes) ?: return null
        return if (value.isFinite()) value else null
    }

    /** Сколько байт ответа нужно формуле (самая дальняя использованная переменная). */
    val requiredBytes: Int = root.maxVariable() + 1

    sealed interface Node {
        fun eval(bytes: IntArray): Double?
        fun maxVariable(): Int
    }

    private class Const(val value: Double) : Node {
        override fun eval(bytes: IntArray) = value
        override fun maxVariable() = -1
    }

    private class Variable(val index: Int) : Node {
        override fun eval(bytes: IntArray) = bytes.getOrNull(index)?.toDouble()
        override fun maxVariable() = index
    }

    private class Negate(val child: Node) : Node {
        override fun eval(bytes: IntArray) = child.eval(bytes)?.let { -it }
        override fun maxVariable() = child.maxVariable()
    }

    private class Binary(val op: Char, val left: Node, val right: Node) : Node {
        override fun eval(bytes: IntArray): Double? {
            val a = left.eval(bytes) ?: return null
            val b = right.eval(bytes) ?: return null
            return when (op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> if (b == 0.0) null else a / b
                else -> null
            }
        }

        override fun maxVariable() = maxOf(left.maxVariable(), right.maxVariable())
    }

    /** Результат разбора: либо формула, либо понятное человеку сообщение. */
    sealed interface Compiled {
        data class Ok(val formula: Formula) : Compiled
        data class Error(val ru: String, val en: String) : Compiled
    }

    companion object {

        fun compile(text: String): Compiled {
            val parser = Parser(text)
            return try {
                val node = parser.parseExpression()
                parser.skipSpaces()
                if (!parser.atEnd()) {
                    Compiled.Error(
                        "Лишний символ в позиции ${parser.position + 1}",
                        "Unexpected character at position ${parser.position + 1}"
                    )
                } else if (node.maxVariable() < 0 && !text.any { it.isDigit() }) {
                    Compiled.Error("Формула пустая", "The formula is empty")
                } else {
                    Compiled.Ok(Formula(node, text.trim()))
                }
            } catch (error: ParseError) {
                Compiled.Error(error.ru, error.en)
            }
        }

        /** Короткий путь для своих формул, в которых ошибки быть не может. */
        fun of(text: String): Formula = when (val compiled = compile(text)) {
            is Compiled.Ok -> compiled.formula
            is Compiled.Error -> error("Неверная формула \"$text\": ${compiled.ru}")
        }
    }

    private class ParseError(val ru: String, val en: String) : Exception(ru)

    private class Parser(private val source: String) {
        var position = 0

        fun atEnd() = position >= source.length

        fun skipSpaces() {
            while (position < source.length && source[position].isWhitespace()) position++
        }

        fun parseExpression(): Node {
            var left = parseTerm()
            while (true) {
                skipSpaces()
                val op = source.getOrNull(position) ?: return left
                if (op != '+' && op != '-') return left
                position++
                left = Binary(op, left, parseTerm())
            }
        }

        private fun parseTerm(): Node {
            var left = parseUnary()
            while (true) {
                skipSpaces()
                val op = source.getOrNull(position) ?: return left
                if (op != '*' && op != '/') return left
                position++
                left = Binary(op, left, parseUnary())
            }
        }

        private fun parseUnary(): Node {
            skipSpaces()
            if (source.getOrNull(position) == '-') {
                position++
                return Negate(parseUnary())
            }
            if (source.getOrNull(position) == '+') {
                position++
                return parseUnary()
            }
            return parseAtom()
        }

        private fun parseAtom(): Node {
            skipSpaces()
            val ch = source.getOrNull(position)
                ?: throw ParseError("Формула обрывается", "The formula ends unexpectedly")
            if (ch == '(') {
                position++
                val inner = parseExpression()
                skipSpaces()
                if (source.getOrNull(position) != ')') {
                    throw ParseError("Не закрыта скобка", "Unclosed bracket")
                }
                position++
                return inner
            }
            if (ch.isDigit() || ch == '.') {
                val start = position
                while (position < source.length && (source[position].isDigit() || source[position] == '.')) position++
                val number = source.substring(start, position).toDoubleOrNull()
                    ?: throw ParseError("Не похоже на число: ${source.substring(start, position)}",
                        "Not a number: ${source.substring(start, position)}")
                return Const(number)
            }
            val upper = ch.uppercaseChar()
            if (upper in 'A'..'H') {
                position++
                return Variable(upper - 'A')
            }
            throw ParseError(
                "Непонятный символ '$ch': в формуле бывают числа, A..H, + - * / и скобки",
                "Unexpected character '$ch': formulas take numbers, A..H, + - * / and brackets"
            )
        }
    }
}
