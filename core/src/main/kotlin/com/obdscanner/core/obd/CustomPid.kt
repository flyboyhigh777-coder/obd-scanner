package com.obdscanner.core.obd

/**
 * Параметр, добавленный пользователем: номер PID и формула пересчёта.
 *
 * Зачем: стандартом описано полторы сотни параметров, а каждый производитель
 * добавляет свои в режиме 22 (например, температура масла АКПП или угол
 * дросселя по второму датчику). Их значения расходятся по форумам в виде
 * "PID 22 1148, (A*256+B)/10 минус 40" - и такую строку должно быть можно
 * просто вписать, не пересобирая приложение.
 *
 * [pid] для режима 01 - один байт, для режима 22 - два.
 */
data class CustomPid(
    val name: String,
    val mode: Int,
    val pid: Int,
    val dataBytes: Int,
    val formulaText: String,
    val unitLabel: String = "",
    val min: Double = 0.0,
    val max: Double = 100.0,
    val decimals: Int = 1
) {

    private val formula: Formula? = (Formula.compile(formulaText) as? Formula.Compiled.Ok)?.formula

    val valid: Boolean get() = formula != null && dataBytes in 1..8 && mode in listOf(0x01, 0x22)

    /** Текст запроса для адаптера: "0105" или "221148". */
    val request: String
        get() = if (mode == 0x22) "22%04X".format(pid) else "%02X%02X".format(mode, pid)

    /** Байты эха PID в ответе: один для режима 01, два для режима 22. */
    val echoBytes: IntArray
        get() = if (mode == 0x22) intArrayOf((pid shr 8) and 0xFF, pid and 0xFF) else intArrayOf(pid)

    fun decode(data: IntArray): Double? = formula?.evaluate(data)

    fun format(value: Double): String {
        val text = "%.${decimals}f".format(value)
        return if (unitLabel.isEmpty()) text else "$text $unitLabel"
    }

    companion object {
        /**
         * Примеры для экрана добавления: они же показывают синтаксис. Это
         * стандартные PID режима 01, то есть заведомо работающие на любой
         * машине - проверить формулу можно, не зная чужих номеров.
         */
        val EXAMPLES: List<CustomPid> = listOf(
            CustomPid(
                name = "Обороты (пример)",
                mode = 0x01, pid = 0x0C, dataBytes = 2,
                formulaText = "(A*256+B)/4", unitLabel = "об/мин", min = 0.0, max = 8000.0, decimals = 0
            ),
            CustomPid(
                name = "Температура ОЖ (пример)",
                mode = 0x01, pid = 0x05, dataBytes = 1,
                formulaText = "A-40", unitLabel = "°C", min = -40.0, max = 130.0, decimals = 0
            )
        )
    }
}
