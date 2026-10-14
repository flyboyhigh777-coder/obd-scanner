package com.obdscanner.core

import com.obdscanner.core.elm.ElmReply
import com.obdscanner.core.elm.ObdPayload
import com.obdscanner.core.obd.CustomPid
import com.obdscanner.core.obd.Formula
import com.obdscanner.core.obd.FuelEconomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Формулы пользовательских PID: разбор, вычисление и понятные ошибки. */
class FormulaTest {

    private fun eval(text: String, vararg bytes: Int): Double? {
        val compiled = Formula.compile(text)
        assertTrue("должно скомпилироваться: $text -> $compiled", compiled is Formula.Compiled.Ok)
        return (compiled as Formula.Compiled.Ok).formula.evaluate(bytes.toList().toIntArray())
    }

    /** Стандартные формулы из описаний PID. */
    @Test
    fun standardFormulas() {
        assertEquals(1726.0, eval("(A*256+B)/4", 0x1A, 0xF8)!!, 1e-9)
        assertEquals(83.0, eval("A-40", 123)!!, 1e-9)
        assertEquals(0.0, eval("A/2-64", 128)!!, 1e-9)
        assertEquals(100.0, eval("100/255*A", 255)!!, 1e-9)
        assertEquals(40.0, eval("(256*A + B) / 100", 0x0F, 0xA0)!!, 1e-9)
        assertEquals(577.2, eval("(A*256+B)/10 - 40", 0x18, 0x1C)!!, 1e-9)
    }

    /** Приоритет операций и скобки. */
    @Test
    fun precedenceAndBrackets() {
        assertEquals(14.0, eval("2+3*4")!!, 1e-9)
        assertEquals(20.0, eval("(2+3)*4")!!, 1e-9)
        assertEquals(-7.0, eval("-7")!!, 1e-9)
        assertEquals(-10.0, eval("-A*2", 5)!!, 1e-9)
        assertEquals(2.5, eval("10/2/2")!!, 1e-9)
        assertEquals(1.5, eval("0.5+1")!!, 1e-9)
    }

    /** Переменные - это байты ответа по порядку. */
    @Test
    fun variablesAreResponseBytes() {
        assertEquals(1.0, eval("A", 1, 2, 3, 4)!!, 1e-9)
        assertEquals(4.0, eval("D", 1, 2, 3, 4)!!, 1e-9)
        assertEquals(10.0, eval("A+B+C+D", 1, 2, 3, 4)!!, 1e-9)
        // Регистр не важен: в описаниях пишут и так, и так
        assertEquals(3.0, eval("a+b", 1, 2)!!, 1e-9)
    }

    /** Байт, которого нет в ответе, даёт null, а не ноль. */
    @Test
    fun missingByteGivesNull() {
        assertNull(eval("A*256+B", 0x1A))
        assertNull(eval("D", 1, 2))
    }

    /** Деление на ноль не роняет приложение. */
    @Test
    fun divisionByZeroIsNull() {
        assertNull(eval("A/B", 10, 0))
        assertNull(eval("10/0"))
    }

    /** Сколько байт нужно формуле - считается по самой дальней переменной. */
    @Test
    fun requiredBytes() {
        val formula = (Formula.compile("(A*256+B)/4") as Formula.Compiled.Ok).formula
        assertEquals(2, formula.requiredBytes)
        assertEquals(4, (Formula.compile("D") as Formula.Compiled.Ok).formula.requiredBytes)
    }

    /** Ошибки объясняются по-человечески, с позицией. */
    @Test
    fun errorsAreReadable() {
        val unclosed = Formula.compile("(A*256")
        assertTrue(unclosed is Formula.Compiled.Error)
        assertTrue((unclosed as Formula.Compiled.Error).ru.contains("скобка"))

        val junk = Formula.compile("A ? B")
        assertTrue(junk is Formula.Compiled.Error)
        assertTrue((junk as Formula.Compiled.Error).ru.contains("символ"))

        assertTrue(Formula.compile("A*") is Formula.Compiled.Error)
        assertTrue(Formula.compile("") is Formula.Compiled.Error)
        assertTrue(Formula.compile("Z+1") is Formula.Compiled.Error)
    }

    /** Пользовательский PID режима 01: запрос, разбор ответа, формат. */
    @Test
    fun customPidMode01() {
        val pid = CustomPid.EXAMPLES.first()
        assertTrue(pid.valid)
        assertEquals("010C", pid.request)
        val reply = ElmReply.parse("41 0C 1A F8\r\r>")
        val data = ObdPayload.data(reply, pid.mode, pid.echoBytes, pid.dataBytes)!!
        assertEquals(1726.0, pid.decode(data)!!, 1e-9)
        assertEquals("1726 об/мин", pid.format(1726.0))
    }

    /** Режим 22: номер параметра двухбайтный, ответ 62 xx xx ... */
    @Test
    fun customPidMode22() {
        val pid = CustomPid(
            name = "Температура масла АКПП",
            mode = 0x22, pid = 0x1148, dataBytes = 2,
            formulaText = "(A*256+B)/10-40", unitLabel = "°C", decimals = 1
        )
        assertTrue(pid.valid)
        assertEquals("221148", pid.request)
        val reply = ElmReply.parse("62 11 48 02 F1\r\r>")
        val data = ObdPayload.data(reply, pid.mode, pid.echoBytes, pid.dataBytes)!!
        // 0x02F1 = 753 -> 75.3 - 40 = 35.3
        assertEquals(35.3, pid.decode(data)!!, 1e-9)
    }

    /** Ответ на другой параметр режима 22 не принимается за свой. */
    @Test
    fun mode22RejectsOtherPid() {
        val pid = CustomPid("тест", 0x22, 0x1148, 2, "A*256+B")
        assertNull(ObdPayload.data(ElmReply.parse("62 11 49 02 F1\r\r>"), 0x22, pid.echoBytes, 2))
    }

    /** Негодный PID отмечается как негодный, а не падает при чтении. */
    @Test
    fun invalidPidIsFlagged() {
        assertFalse(CustomPid("плохой", 0x01, 0x0C, 2, "A*(").valid)
        assertFalse(CustomPid("плохой режим", 0x03, 0x0C, 2, "A").valid)
        assertFalse(CustomPid("много байт", 0x01, 0x0C, 99, "A").valid)
    }
    /**
     * Расход по давлению во впуске - единственный способ показать расход на
     * машине без расходомера. Проверяем на холостых ЗМЗ-409: 2.7 л, 874
     * об/мин, 36 кПа, воздух 55 градусов.
     */
    @Test
    fun airFlowFromManifoldPressure() {
        val maf = FuelEconomy.mafFromMap(
            rpm = 874.0,
            mapKpa = 36.0,
            intakeTempC = 55.0,
            displacementL = 2.693,
        )
        assertNotNull(maf)
        // Около 6 г/с - столько и проходит через мотор на холостых.
        assertEquals(6.0, maf!!, 0.5)

        val litresPerHour = FuelEconomy.litresPerHourFromMaf(maf)
        // Холостой ход 2.7-литрового бензинового - около двух литров в час.
        assertTrue("холостой расход $litresPerHour л/ч", litresPerHour in 1.2..3.0)
    }

    /** С газом растёт и давление, и расход - иначе оценка бесполезна. */
    @Test
    fun airFlowGrowsWithLoad() {
        val idle = FuelEconomy.mafFromMap(850.0, 36.0, 55.0, 2.693)!!
        val pull = FuelEconomy.mafFromMap(3000.0, 95.0, 45.0, 2.693)!!
        assertTrue("под нагрузкой больше: $idle -> $pull", pull > idle * 5)
    }

    /** Без оборотов или без давления считать нечего - лучше null, чем ноль. */
    @Test
    fun airFlowNeedsRealNumbers() {
        assertNull(FuelEconomy.mafFromMap(0.0, 36.0, 55.0, 2.693))
        assertNull(FuelEconomy.mafFromMap(850.0, 0.0, 55.0, 2.693))
        assertNull(FuelEconomy.mafFromMap(850.0, 36.0, 55.0, 0.0))
    }

    /**
     * Готовый расход от машины - лучший источник, его и берём.
     */
    @Test
    fun readyFuelRateWins() {
        val rate = FuelEconomy.litresPerHour(
            fuelRate = 2.4,
            maf = 6.0,
            rpm = 850.0,
            mapKpa = 36.0,
            displacementL = 2.693,
        )
        assertEquals(2.4, rate!!.litresPerHour, 0.001)
        assertFalse("это измерение, а не оценка", rate.estimated)
    }

    /**
     * Блок ЗАЯВИЛ расходомер, но на него не отвечает - и расход пропадал
     * совсем. Теперь цепочка идёт дальше, к давлению во впуске.
     *
     * Ровно этот случай и был на живой машине: ЗМЗ-409 не отдаёт ни 0x10, ни
     * 0x5E, и расход показывал прочерк.
     */
    @Test
    fun silentAirflowFallsThroughToManifoldPressure() {
        val rate = FuelEconomy.litresPerHour(
            fuelRate = null,
            maf = null,
            rpm = 874.0,
            mapKpa = 36.0,
            intakeTempC = 55.0,
            displacementL = 2.693,
        )
        assertNotNull("расход посчитан по давлению", rate)
        assertTrue("и помечен как расчётный", rate!!.estimated)
        assertTrue("холостой ход похож на правду: ${rate.litresPerHour}", rate.litresPerHour in 1.2..3.0)
    }

    /** Температуру впуска машина отдаёт не всегда - это не повод не считать. */
    @Test
    fun manifoldPathWorksWithoutIntakeTemperature() {
        val rate = FuelEconomy.litresPerHour(
            rpm = 874.0,
            mapKpa = 36.0,
            intakeTempC = null,
            displacementL = 2.693,
        )
        assertNotNull(rate)
        assertTrue(rate!!.estimated)
    }

    /** А вот без оборотов или давления считать действительно нечего. */
    @Test
    fun nothingToComputeFrom() {
        assertNull(FuelEconomy.litresPerHour(rpm = 874.0, mapKpa = null, displacementL = 2.693))
        assertNull(FuelEconomy.litresPerHour(rpm = null, mapKpa = 36.0, displacementL = 2.693))
        assertNull(FuelEconomy.litresPerHour(displacementL = 2.693))
    }

    /** Коэффициент наполнения двигает результат - иначе подгонять было бы нечем. */
    @Test
    fun volumetricEfficiencyChangesTheResult() {
        val low = FuelEconomy.litresPerHour(
            rpm = 874.0, mapKpa = 36.0, intakeTempC = 55.0,
            displacementL = 2.693, volumetricEfficiency = 0.6,
        )!!.litresPerHour
        val high = FuelEconomy.litresPerHour(
            rpm = 874.0, mapKpa = 36.0, intakeTempC = 55.0,
            displacementL = 2.693, volumetricEfficiency = 1.0,
        )!!.litresPerHour
        assertTrue("больше наполнение - больше расход: $low -> $high", high > low * 1.5)
    }

}
