package com.obdscanner.core

import com.obdscanner.core.monitor.MonitorIds
import com.obdscanner.core.monitor.MonitorSupport
import com.obdscanner.core.monitor.MonitorTest
import com.obdscanner.core.monitor.O2SensorLocation
import com.obdscanner.core.monitor.O2SensorTest
import com.obdscanner.core.monitor.UnitScalings
import com.obdscanner.core.session.ElmAdapter
import com.obdscanner.core.session.ObdSession
import com.obdscanner.core.sim.SimulatedEcu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Режимы 05 и 06 - результаты бортовых самодиагностик. Проверяется и разбор
 * байтов (числа взяты из таблиц SAE J1979), и сквозное чтение через сессию
 * против симулятора ЭБУ.
 */
class MonitorTestsTest {

    private class FakeClock(var millis: Long = 10_000) {
        fun now(): Long = millis
        fun sleep(ms: Long) { millis += ms }
    }

    private fun connected(): Pair<ObdSession, SimulatedEcu> {
        val clock = FakeClock()
        val ecu = SimulatedEcu(clock = clock::now)
        val session = ObdSession(ElmAdapter(ecu, sleep = clock::sleep, now = clock::now))
        session.connect()
        return session to ecu
    }

    @Test
    /** единицы и масштабы: температура со сдвигом, напряжение, знаковые. */
    fun unitScalingTable() {
        // 0x16 - температура: 0,1 °C на отсчёт и сдвиг -40.
        val temp = UnitScalings.of(0x16)!!
        assertEquals(-40.0, temp.value(0), 1e-9)
        assertEquals(60.0, temp.value(1000), 1e-9)
        // 0x0C - напряжение с шагом 0,01 В.
        assertEquals(4.86, UnitScalings.of(0x0C)!!.value(486), 1e-9)
        // 0x10 - миллисекунды, один к одному.
        assertEquals(48.0, UnitScalings.of(0x10)!!.value(48), 1e-9)
        // Знаковые идентификаторы помечены, беззнаковые - нет.
        assertTrue(UnitScalings.of(0xAF)!!.signed)
        assertFalse(UnitScalings.of(0x2F)!!.signed)
        // Чего в стандарте нет, то и не выдумывается.
        assertNull(UnitScalings.of(0x40))
        assertTrue(UnitScalings.isManufacturerRange(0x40))
    }

    @Test
    /** запись режима 06 по CAN: монитор, тест, значение и пределы. */
    fun decodesCanRecords() {
        // 46 | 21 80 24 002A 0000 0024 - катализатор банка 1, 42 отсчёта при
        // пределе 36: тест провален.
        val payload = intArrayOf(0x21, 0x80, 0x24, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x24)
        val tests = MonitorTest.decodeCan(payload)
        assertEquals(1, tests.size)
        val test = tests[0]
        assertEquals(0x21, test.mid)
        assertEquals(0x80, test.tid)
        assertEquals(42.0, test.value!!, 1e-9)
        assertEquals(0.0, test.min!!, 1e-9)
        assertEquals(36.0, test.max!!, 1e-9)
        assertEquals(false, test.passed)
        assertTrue(MonitorIds.name(0x21).isNotEmpty())
    }

    @Test
    /** несколько записей в одном ответе; добивка кадра нулями не считается тестом. */
    fun decodesSeveralRecordsAndIgnoresPadding() {
        val payload = intArrayOf(
            0x01, 0x05, 0x10, 0x00, 0x30, 0x00, 0x00, 0x00, 0xC8,
            0x01, 0x08, 0x0C, 0x00, 0x56, 0x00, 0x3C, 0x00, 0x6E,
            // Хвост, которым ЭБУ добивает последний кадр CAN.
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val tests = MonitorTest.decodeCan(payload)
        assertEquals(2, tests.size)
        // Первый - время переключения зонда: 48 мс при пределе 200.
        assertEquals(48.0, tests[0].value!!, 1e-9)
        assertEquals(true, tests[0].passed)
        // Второй - максимальное напряжение за цикл: 0,86 В в пределах 0,60-1,10.
        assertEquals(0.86, tests[1].value!!, 1e-9)
        assertEquals(true, tests[1].passed)
        // Положение между пределами - то, что рисует шкала.
        assertEquals(0.52, tests[1].position!!, 0.01)
    }

    @Test
    /** старый формат: предел один, а какой он - говорит старший бит. */
    fun decodesLegacyRecord() {
        // Старший бит байта CID выставлен - значит предел нижний.
        val minimum = MonitorTest.decodeLegacy(intArrayOf(0x05, 0x81, 0x00, 0x64, 0x00, 0x32))!!
        assertEquals(100, minimum.rawValue)
        assertEquals(50, minimum.rawMin)
        assertNull(minimum.rawMax)
        assertEquals(true, minimum.passed)

        // Бит снят - предел верхний, и значение его превышает.
        val maximum = MonitorTest.decodeLegacy(intArrayOf(0x05, 0x01, 0x00, 0x64, 0x00, 0x32))!!
        assertEquals(50, maximum.rawMax)
        assertNull(maximum.rawMin)
        assertEquals(false, maximum.passed)
        // Масштаба в старом формате нет - значит показываются отсчёты.
        assertNull(maximum.value)
        assertTrue(maximum.formatValue().contains("100"))
    }

    @Test
    /** знаковые значения читаются как дополнительный код, а не как 65000. */
    fun decodesSignedValues() {
        // 0xAF - проценты со знаком, шаг 0,01: 0xFFFF это -0,01 %.
        val test = MonitorTest(mid = 0x81, tid = 0x80, uasId = 0xAF, rawValue = 0xFFFF, rawMin = null, rawMax = null)
        assertEquals(-0.01, test.value!!, 1e-9)
    }

    @Test
    /** карта поддерживаемых мониторов: разбор и обратная сборка согласованы. */
    fun monitorSupportRoundTrip() {
        val mids = setOf(0x01, 0x02, 0x20)
        val encoded = MonitorSupport.encode(0x00, mids)
        assertEquals(mids, MonitorSupport.decode(0x00, encoded))
        assertTrue(MonitorSupport.hasNextRange(0x00, mids))
        assertFalse(MonitorSupport.hasNextRange(0x20, mids))
    }

    @Test
    /** сессия доходит до мониторов из последней карты, а не только из первой. */
    fun sessionReadsAllMonitorIds() {
        val (session, ecu) = connected()
        val mids = session.readSupportedMonitorIds()
        // Номера карт - это служебные биты, в списке мониторов их быть не должно.
        assertFalse(0x20 in mids)
        assertTrue(0x01 in mids)
        // Пропуски воспламенения лежат в последней карте: если перебор
        // останавливается на первой, их не видно.
        assertTrue(0xA1 in mids)
        assertEquals(ecu.supportedMonitorIds.filter { it !in MonitorSupport.RANGE_MIDS }.toSet(), mids)
    }

    @Test
    /** полное чтение режима 06 находит провалённый тест катализатора. */
    fun sessionReadsMonitorTests() {
        val (session, _) = connected()
        val tests = session.readAllMonitorTests()
        assertTrue(tests.size >= 8)
        val catalyst = tests.first { it.mid == 0x21 }
        assertEquals(false, catalyst.passed)
        // И он единственный провалившийся - остальные в пределах.
        assertEquals(1, tests.count { it.passed == false })
    }

    @Test
    /** режим 05: масштаб задан номером теста, а не байтом единиц. */
    fun decodesO2SensorTest() {
        // 45 | 05 01 7D - время переключения, 125 отсчётов по 0,004 с.
        val time = O2SensorTest.decode(intArrayOf(0x05, 0x01, 0x7D))!!
        assertEquals(O2SensorTest.Kind.TIME, time.kind)
        assertEquals(0.5, time.value!!, 1e-9)
        assertEquals(1.02, time.max!!, 1e-9)

        // Напряжение - 0,005 В на отсчёт.
        val voltage = O2SensorTest.decode(intArrayOf(0x01, 0x01, 0x64))!!
        assertEquals(O2SensorTest.Kind.VOLTAGE, voltage.kind)
        assertEquals(0.5, voltage.value!!, 1e-9)

        // Период датчика - 0,04 с на отсчёт, предел 10,2 с.
        val period = O2SensorTest.decode(intArrayOf(0x0A, 0x10, 0x19))!!
        assertEquals(1.0, period.value!!, 1e-9)
        assertEquals(10.2, period.max!!, 1e-9)
        // Бит 0x10 - это первый датчик ВТОРОГО банка.
        assertTrue(period.sensorTitle.contains("2"))
    }

    @Test
    /** две раскладки лямбда-зондов дают из одного байта разные датчики. */
    fun o2SensorLocationsDifferByPid() {
        val twoBanks = O2SensorLocation.decodeTwoBanks(0x11)
        val present2 = twoBanks.filter { it.present }
        assertEquals(2, present2.size)
        // 0x11 в раскладке "2 банка по 4" - это банк 1 датчик 1 и банк 2 датчик 1.
        assertEquals(1 to 1, present2[0].bank to present2[0].sensor)
        assertEquals(2 to 1, present2[1].bank to present2[1].sensor)

        val fourBanks = O2SensorLocation.decodeFourBanks(0x11)
        val present4 = fourBanks.filter { it.present }
        // Тот же байт в раскладке "4 банка по 2" - банк 1 датчик 1 и банк 3 датчик 1.
        assertEquals(1 to 1, present4[0].bank to present4[0].sensor)
        assertEquals(3 to 1, present4[1].bank to present4[1].sensor)
        // Бит датчика из запроса режима 05 - это маска, а не номер по порядку.
        assertEquals(0x01, present4[0].mask)
        assertEquals(0x10, present4[1].mask)
    }

    @Test
    /** сессия берёт раскладку по тому PID, который машина объявила. */
    fun sessionReadsO2Locations() {
        val (session, _) = connected()
        val locations = session.readO2SensorLocations()
        assertNotNull(locations)
        // Симулятор отдаёт PID 0x13 со значением 0x03 - два зонда первого банка.
        assertEquals(2, locations.count { it.present })
        assertTrue(locations.filter { it.present }.all { it.bank == 1 })
    }
}
