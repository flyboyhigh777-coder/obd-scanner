package com.obdscanner.core.electrical

import com.obdscanner.core.elm.ObdText
import kotlin.math.abs

/**
 * Аккумулятор и генератор по напряжению бортовой сети.
 *
 * Про ток сразу и честно: измерить его этим способом нельзя. В стандарте
 * OBD-II (SAE J1979) нет ни одного параметра с током - ни потребления, ни
 * зарядного, ни утечки; ЭБУ двигателя его попросту не меряет. Нет его и у
 * ELM327: адаптер умеет сообщить только напряжение на своём разъёме (`AT RV`).
 *
 * Оценить ток косвенно, по скорости падения напряжения, тоже не выйдет, и это
 * считается на цифрах. У свинцово-кислотной АКБ весь рабочий диапазон покоя -
 * около 0.8 В на 100% заряда, то есть 1% ≈ 0.008 В. Для батареи 75 А·ч утечка
 * 50 мА за час съедает 0.05 А·ч - это 0.067% заряда, или 0.0005 В. Даже
 * большая утечка в 3 А даст за час 0.03 В. Разрешение ATRV - 0.1 В, то есть
 * в тридцать с лишним раз грубее. Утечку ищут токовыми клещами или
 * мультиметром в разрыв минусовой клеммы, и приложение это заменить не может.
 *
 * Что по напряжению сказать можно - и что здесь посчитано:
 *
 *  * заряд в покое (таблица ниже) - насколько батарея заряжена;
 *  * просадка при пуске - главный показатель здоровья АКБ и стартера;
 *  * работа генератора - заряжает, недозаряжает или перезаряжает.
 */

/** Что происходит с бортовой сетью. Порог зависит от того, работает ли мотор. */
enum class BatteryVerdict(val ru: String, val en: String, val alarming: Boolean) {
    DEEP_DISCHARGE("Глубокий разряд - батарее вредно", "Deep discharge - harmful to the battery", true),
    DISCHARGED("Разряжена, нужна зарядка", "Discharged, needs charging", true),
    PARTIAL("Подсажена", "Partially charged", false),
    RESTING_OK("Заряжена", "Charged", false),
    NOT_CHARGING("Генератор не заряжает", "Alternator is not charging", true),
    CHARGE_WEAK("Слабый заряд", "Weak charge", true),
    CHARGE_OK("Генератор заряжает нормально", "Alternator charging normally", false),
    OVERCHARGE("Перезаряд - реле-регулятор", "Overcharge - voltage regulator", true),
    UNKNOWN("Нет данных", "No data", false);

    val title: String get() = ObdText.pick(ru, en)
}

/**
 * Насколько велик ток утечки - по меркам обычной машины.
 *
 * Сам ток приложение не меряет и измерить не может (см. комментарий выше);
 * его приносят токовыми клещами или мультиметром в разрыв минусовой клеммы.
 * Здесь только оценка принесённого числа и арифметика "на сколько хватит".
 */
enum class LeakVerdict(val ru: String, val en: String) {
    NORMAL("В норме", "Normal"),
    ELEVATED("Повышенная - стоит поискать", "Elevated - worth investigating"),
    HIGH("Большая - машина сядет за считанные дни", "High - the battery will flatten in days");

    val title: String get() = ObdText.pick(ru, en)
}

/** Насколько просела бортовая сеть при прокрутке стартером. */
enum class CrankVerdict(val ru: String, val en: String) {
    GOOD("Батарея держит пуск хорошо", "Battery holds cranking well"),
    FAIR("Просадка на пределе нормы", "Dip at the edge of normal"),
    WEAK("Сильная просадка: АКБ слаба или плохой контакт", "Deep dip: weak battery or bad contact");

    val title: String get() = ObdText.pick(ru, en)
}

/** Результат пуска: насколько провалилось напряжение и надолго ли. */
data class CrankingResult(
    val restVolts: Double,
    val minVolts: Double,
    val durationMillis: Long,
) {
    /** На сколько вольт просело относительно напряжения перед пуском. */
    val dropVolts: Double get() = restVolts - minVolts

    val verdict: CrankVerdict get() = Battery.crankVerdict(minVolts)
}

object Battery {

    /**
     * Напряжение покоя -> заряд, для свинцово-кислотной батареи 12 В при +20 °C.
     *
     * Таблица, а не формула: зависимость заметно нелинейная, особенно у
     * нижнего края. Значения между узлами берутся линейной интерполяцией.
     *
     * Важно: это именно напряжение ПОКОЯ. Сразу после остановки мотора на
     * клеммах стоит поверхностный заряд, и батарея выглядит бодрее, чем есть -
     * честное значение получается минут через пятнадцать-двадцать.
     */
    private val SOC = listOf(
        11.31 to 0, 11.58 to 10, 11.75 to 20, 11.90 to 30, 12.06 to 40,
        12.20 to 50, 12.32 to 60, 12.42 to 70, 12.50 to 80, 12.62 to 90,
        12.73 to 100,
    )

    fun restingChargePercent(volts: Double): Int {
        if (volts <= SOC.first().first) return 0
        if (volts >= SOC.last().first) return 100
        for (i in 0 until SOC.size - 1) {
            val (loV, loP) = SOC[i]
            val (hiV, hiP) = SOC[i + 1]
            if (volts in loV..hiV) {
                val span = hiV - loV
                // Узлы таблицы различны по построению, но деление на ноль здесь
                // обошлось бы молчаливым NaN в процентах заряда.
                if (span <= 0.0) return loP
                return (loP + (volts - loV) / span * (hiP - loP)).toInt()
            }
        }
        return 100
    }

    /**
     * Оценка состояния. При работающем моторе напряжение задаёт генератор, и
     * пороги совсем другие, поэтому [engineRunning] обязателен: 12.6 В - это
     * отличная заряженная батарея на стоянке и severe недозаряд на ходу.
     */
    fun verdict(volts: Double, engineRunning: Boolean): BatteryVerdict = when {
        volts <= 0.0 -> BatteryVerdict.UNKNOWN
        engineRunning -> when {
            volts < 13.0 -> BatteryVerdict.NOT_CHARGING
            volts < 13.6 -> BatteryVerdict.CHARGE_WEAK
            volts <= 14.8 -> BatteryVerdict.CHARGE_OK
            else -> BatteryVerdict.OVERCHARGE
        }
        volts < 11.8 -> BatteryVerdict.DEEP_DISCHARGE
        volts < 12.2 -> BatteryVerdict.DISCHARGED
        volts < 12.5 -> BatteryVerdict.PARTIAL
        else -> BatteryVerdict.RESTING_OK
    }

    /**
     * Порог 9.6 В - общепринятый для теста прокрутки при комнатной
     * температуре: ниже него стартер уже на грани, и виновата либо севшая
     * батарея, либо окисленные клеммы.
     */
    fun crankVerdict(minVolts: Double): CrankVerdict = when {
        minVolts >= 10.5 -> CrankVerdict.GOOD
        minVolts >= 9.6 -> CrankVerdict.FAIR
        else -> CrankVerdict.WEAK
    }

    /** Обороты, выше которых считаем, что мотор работает, а не крутится стартером. */
    const val RUNNING_RPM = 400.0

    /**
     * Ниже этого заряда машина зимой уже может не завестись - до него и
     * считаем "сколько простоит". Полный разряд брать нельзя: до него дело
     * доходит уже после того, как человек остался без машины.
     */
    const val CRANK_FLOOR_PERCENT = 50

    /**
     * Обычная машина в покое потребляет десятки миллиампер: сигнализация,
     * магнитола с памятью, блоки в спящем режиме. Пороги ориентировочные - на
     * машине с несколькими дополнительными блоками норма будет выше.
     */
    fun leakVerdict(milliAmps: Double): LeakVerdict = when {
        milliAmps <= 50.0 -> LeakVerdict.NORMAL
        milliAmps <= 80.0 -> LeakVerdict.ELEVATED
        else -> LeakVerdict.HIGH
    }

    /**
     * Сколько суток машина простоит с известной утечкой, пока заряд не упадёт
     * до [floorPercent]. Чистая арифметика по ёмкости - без температуры и
     * саморазряда, поэтому в холод будет меньше.
     */
    fun daysUntilFlat(
        batteryAh: Double,
        chargePercent: Int,
        milliAmps: Double,
        floorPercent: Int = CRANK_FLOOR_PERCENT,
    ): Double? {
        if (batteryAh <= 0.0 || milliAmps <= 0.0) return null
        val usableShare = (chargePercent - floorPercent).coerceAtLeast(0) / 100.0
        if (usableShare <= 0.0) return 0.0
        val hours = batteryAh * usableShare / (milliAmps / 1000.0)
        return hours / 24.0
    }
}

/**
 * Накопитель замеров напряжения: минимум, максимум и поиск просадки при пуске.
 *
 * Пуск ловится отдельно от обычного опроса: прокрутка длится меньше секунды, и
 * на обычном периоде опроса (300 мс на все параметры) провал проскакивает между
 * замерами. Поэтому экран теста пуска опрашивает только напряжение и часто.
 */
class BatteryMonitor(
    /** Ниже этого напряжения считаем, что началась просадка. */
    private val dipEnter: Double = 11.5,
    /** Выше этого - просадка закончилась. */
    private val dipExit: Double = 12.2,
    /** Дольше этого прокрутка не длится: иначе это не пуск, а разряд. */
    private val maxDipMillis: Long = 15_000,
) {

    data class Sample(val timeMillis: Long, val volts: Double, val engineRunning: Boolean)

    private val _samples = ArrayList<Sample>()
    val samples: List<Sample> get() = _samples

    var minVolts: Double? = null
        private set
    var maxVolts: Double? = null
        private set

    /** Последняя пойманная просадка при пуске, если она была. */
    var cranking: CrankingResult? = null
        private set

    private var dipStart: Long? = null
    private var dipMin: Double = Double.MAX_VALUE
    private var beforeDip: Double? = null

    val last: Sample? get() = _samples.lastOrNull()

    /** Обычный опрос: работает ли мотор, видно по оборотам. */
    fun add(timeMillis: Long, volts: Double, rpm: Double? = null) =
        add(timeMillis, volts, (rpm ?: 0.0) >= Battery.RUNNING_RPM)

    /**
     * Тест пуска: обороты в это время не опрашиваются (весь период уходит на
     * само напряжение), и факт работы мотора передаётся явно - по тому, поднял
     * ли генератор бортовую сеть.
     */
    fun add(timeMillis: Long, volts: Double, engineRunning: Boolean) {
        if (volts <= 0.0 || !volts.isFinite()) return
        val running = engineRunning
        _samples.add(Sample(timeMillis, volts, running))
        minVolts = minOf(minVolts ?: volts, volts)
        maxVolts = maxOf(maxVolts ?: volts, volts)

        val start = dipStart
        if (start == null) {
            if (volts < dipEnter) {
                dipStart = timeMillis
                dipMin = volts
                // Напряжение перед провалом: предпоследний замер, он ещё "до".
                beforeDip = _samples.getOrNull(_samples.size - 2)?.volts ?: volts
            }
            return
        }

        dipMin = minOf(dipMin, volts)
        val tooLong = timeMillis - start > maxDipMillis
        if (volts >= dipExit || tooLong) {
            if (!tooLong) {
                cranking = CrankingResult(
                    restVolts = beforeDip ?: volts,
                    minVolts = dipMin,
                    durationMillis = timeMillis - start,
                )
            }
            dipStart = null
            dipMin = Double.MAX_VALUE
            beforeDip = null
        }
    }

    /** Текущая оценка - по последнему замеру. */
    fun verdict(): BatteryVerdict {
        val s = last ?: return BatteryVerdict.UNKNOWN
        return Battery.verdict(s.volts, s.engineRunning)
    }

    /**
     * Заряд в процентах - только когда мотор не работает: при работающем
     * генераторе напряжение к заряду батареи отношения не имеет.
     */
    fun restingChargePercent(): Int? {
        val s = last ?: return null
        if (s.engineRunning) return null
        return Battery.restingChargePercent(s.volts)
    }

    /** Разброс напряжения за сессию - заметный скачок виден и без графика. */
    fun spreadVolts(): Double? {
        val lo = minVolts ?: return null
        val hi = maxVolts ?: return null
        return abs(hi - lo)
    }

    fun reset() {
        _samples.clear()
        minVolts = null
        maxVolts = null
        cranking = null
        dipStart = null
        dipMin = Double.MAX_VALUE
        beforeDip = null
    }
}
