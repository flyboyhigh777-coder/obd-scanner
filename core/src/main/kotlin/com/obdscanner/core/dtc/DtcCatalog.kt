package com.obdscanner.core.dtc

import com.obdscanner.core.elm.ObdText

/**
 * Расшифровка кодов неисправностей.
 *
 * Здесь только обобщённые (generic) коды SAE J2012 - те, что означают одно и то
 * же на любой машине. Коды производителя (P1xxx, P3xxx и x1xxx у C/B/U) в
 * стандарт не входят: один и тот же номер у Toyota и у VW значит разное, и
 * честный ответ для них - "код производителя, смотрите по марке", а не
 * выдуманная расшифровка. Для таких кодов (и для generic, которого нет в
 * таблице) [describe] возвращает описание семейства по номеру - оно всегда
 * осмысленно, потому что семейства в J2012 закреплены за системами.
 *
 * Таблица разбита на функции по системам: так её проще дополнять, и ни один
 * метод не растёт до предела в 64 КБ байт-кода, который есть у JVM.
 */
object DtcCatalog {

    private val TABLE: Map<String, Pair<String, String>> by lazy {
        buildMap {
            putAll(fuelAndAir())
            putAll(oxygenSensors())
            putAll(ignitionAndMisfire())
            putAll(emissions())
            putAll(speedIdleAndOutputs())
            putAll(transmission())
            putAll(extraStandard())
            putAll(networkChassisBody())
        }
    }

    private fun fuelAndAir(): Map<String, Pair<String, String>> = mapOf(
        "P0010" to ("Цепь привода фазорегулятора распредвала A, банк 1" to "Camshaft position actuator A circuit (bank 1)"),
        "P0011" to ("Фазы распредвала A слишком ранние или система не держит угол, банк 1" to "Camshaft position - timing over-advanced (bank 1)"),
        "P0012" to ("Фазы распредвала A слишком поздние, банк 1" to "Camshaft position - timing over-retarded (bank 1)"),
        "P0013" to ("Цепь привода фазорегулятора выпускного распредвала, банк 1" to "Exhaust camshaft position actuator circuit (bank 1)"),
        "P0014" to ("Фазы выпускного распредвала слишком ранние, банк 1" to "Exhaust camshaft timing over-advanced (bank 1)"),
        "P0016" to ("Рассогласование датчиков коленвала и распредвала A, банк 1 (перескочил ремень/цепь ГРМ)" to "Crankshaft / camshaft position correlation (bank 1 sensor A)"),
        "P0017" to ("Рассогласование датчиков коленвала и распредвала B, банк 1" to "Crankshaft / camshaft position correlation (bank 1 sensor B)"),
        "P0020" to ("Цепь привода фазорегулятора распредвала A, банк 2" to "Camshaft position actuator A circuit (bank 2)"),
        "P0021" to ("Фазы распредвала A слишком ранние, банк 2" to "Camshaft position - timing over-advanced (bank 2)"),
        "P0022" to ("Фазы распредвала A слишком поздние, банк 2" to "Camshaft position - timing over-retarded (bank 2)"),
        "P0053" to ("Сопротивление подогрева лямбда-зонда 1, банк 1" to "HO2S heater resistance (bank 1 sensor 1)"),
        "P0100" to ("Цепь датчика массового расхода воздуха (ДМРВ)" to "Mass air flow sensor circuit"),
        "P0101" to ("Показания ДМРВ вне допустимого диапазона (подсос воздуха, грязный датчик)" to "Mass air flow sensor range / performance"),
        "P0102" to ("Низкий сигнал ДМРВ" to "Mass air flow sensor circuit low input"),
        "P0103" to ("Высокий сигнал ДМРВ" to "Mass air flow sensor circuit high input"),
        "P0105" to ("Цепь датчика абсолютного давления во впуске (MAP)" to "Manifold absolute pressure sensor circuit"),
        "P0106" to ("Показания датчика давления во впуске вне диапазона" to "Manifold absolute pressure sensor range / performance"),
        "P0107" to ("Низкий сигнал датчика давления во впуске" to "Manifold absolute pressure sensor low input"),
        "P0108" to ("Высокий сигнал датчика давления во впуске" to "Manifold absolute pressure sensor high input"),
        "P0110" to ("Цепь датчика температуры воздуха на впуске" to "Intake air temperature sensor circuit"),
        "P0111" to ("Показания датчика температуры воздуха вне диапазона" to "Intake air temperature sensor range / performance"),
        "P0112" to ("Низкий сигнал датчика температуры воздуха" to "Intake air temperature sensor low input"),
        "P0113" to ("Высокий сигнал датчика температуры воздуха (обрыв)" to "Intake air temperature sensor high input"),
        "P0115" to ("Цепь датчика температуры охлаждающей жидкости" to "Engine coolant temperature sensor circuit"),
        "P0116" to ("Показания датчика температуры ОЖ вне диапазона" to "Engine coolant temperature sensor range / performance"),
        "P0117" to ("Низкий сигнал датчика температуры ОЖ" to "Engine coolant temperature sensor low input"),
        "P0118" to ("Высокий сигнал датчика температуры ОЖ (обрыв цепи)" to "Engine coolant temperature sensor high input"),
        "P0120" to ("Цепь датчика положения дросселя A" to "Throttle position sensor A circuit"),
        "P0121" to ("Показания датчика положения дросселя A вне диапазона" to "Throttle position sensor A range / performance"),
        "P0122" to ("Низкий сигнал датчика положения дросселя A" to "Throttle position sensor A low input"),
        "P0123" to ("Высокий сигнал датчика положения дросселя A" to "Throttle position sensor A high input"),
        "P0125" to ("Двигатель не прогревается до температуры замкнутого контура" to "Insufficient coolant temperature for closed loop fuel control"),
        "P0128" to ("Двигатель не выходит на рабочую температуру (обычно термостат)" to "Coolant thermostat below regulating temperature"),
        "P0170" to ("Ошибка коррекции топливоподачи, банк 1" to "Fuel trim malfunction (bank 1)"),
        "P0171" to ("Слишком бедная смесь, банк 1 (подсос воздуха, слабый насос, грязные форсунки)" to "System too lean (bank 1)"),
        "P0172" to ("Слишком богатая смесь, банк 1 (льют форсунки, высокое давление топлива)" to "System too rich (bank 1)"),
        "P0173" to ("Ошибка коррекции топливоподачи, банк 2" to "Fuel trim malfunction (bank 2)"),
        "P0174" to ("Слишком бедная смесь, банк 2" to "System too lean (bank 2)"),
        "P0175" to ("Слишком богатая смесь, банк 2" to "System too rich (bank 2)"),
        "P0180" to ("Цепь датчика температуры топлива A" to "Fuel temperature sensor A circuit"),
        "P0190" to ("Цепь датчика давления в топливной рейке" to "Fuel rail pressure sensor circuit"),
        "P0191" to ("Показания датчика давления в рейке вне диапазона" to "Fuel rail pressure sensor range / performance"),
        "P0192" to ("Низкий сигнал датчика давления в рейке" to "Fuel rail pressure sensor low input"),
        "P0193" to ("Высокий сигнал датчика давления в рейке" to "Fuel rail pressure sensor high input"),
        "P0200" to ("Цепь форсунок" to "Injector circuit malfunction"),
        "P0201" to ("Цепь форсунки цилиндра 1" to "Injector circuit - cylinder 1"),
        "P0202" to ("Цепь форсунки цилиндра 2" to "Injector circuit - cylinder 2"),
        "P0203" to ("Цепь форсунки цилиндра 3" to "Injector circuit - cylinder 3"),
        "P0204" to ("Цепь форсунки цилиндра 4" to "Injector circuit - cylinder 4"),
        "P0205" to ("Цепь форсунки цилиндра 5" to "Injector circuit - cylinder 5"),
        "P0206" to ("Цепь форсунки цилиндра 6" to "Injector circuit - cylinder 6"),
        "P0217" to ("Перегрев двигателя" to "Engine over temperature condition"),
        "P0219" to ("Превышение максимальных оборотов двигателя" to "Engine overspeed condition"),
        "P0220" to ("Цепь датчика положения дросселя B" to "Throttle position sensor B circuit"),
        "P0221" to ("Показания датчика положения дросселя B вне диапазона" to "Throttle position sensor B range / performance"),
        "P0222" to ("Низкий сигнал датчика положения дросселя B" to "Throttle position sensor B low input"),
        "P0223" to ("Высокий сигнал датчика положения дросселя B" to "Throttle position sensor B high input"),
        "P0230" to ("Цепь управления бензонасосом" to "Fuel pump primary circuit"),
        "P0231" to ("Низкий сигнал вторичной цепи бензонасоса" to "Fuel pump secondary circuit low"),
        "P0232" to ("Высокий сигнал вторичной цепи бензонасоса" to "Fuel pump secondary circuit high"),
        "P0234" to ("Превышение давления наддува (overboost)" to "Turbocharger overboost condition"),
        "P0235" to ("Цепь датчика давления наддува A" to "Turbocharger boost sensor A circuit"),
        "P0236" to ("Показания датчика давления наддува A вне диапазона" to "Turbocharger boost sensor A range / performance"),
        "P0237" to ("Низкий сигнал датчика давления наддува A" to "Turbocharger boost sensor A circuit low"),
        "P0238" to ("Высокий сигнал датчика давления наддува A" to "Turbocharger boost sensor A circuit high"),
        "P0243" to ("Цепь клапана управления перепускной заслонкой турбины A" to "Turbocharger wastegate solenoid A"),
        "P0245" to ("Низкий сигнал клапана перепускной заслонки турбины A" to "Turbocharger wastegate solenoid A low"),
        "P0246" to ("Высокий сигнал клапана перепускной заслонки турбины A" to "Turbocharger wastegate solenoid A high"),
        "P0251" to ("Дозирование топлива ТНВД A" to "Injection pump fuel metering control A"),
        "P0263" to ("Разброс вклада цилиндра 1" to "Cylinder 1 contribution / balance fault"),
        "P0266" to ("Разброс вклада цилиндра 2" to "Cylinder 2 contribution / balance fault"),
        "P0269" to ("Разброс вклада цилиндра 3" to "Cylinder 3 contribution / balance fault"),
        "P0272" to ("Разброс вклада цилиндра 4" to "Cylinder 4 contribution / balance fault"),
        "P0299" to ("Недостаточное давление наддува (underboost): подсос в интеркулере, геометрия турбины" to "Turbocharger underboost condition"),
        "P0300" to ("Пропуски воспламенения в случайных цилиндрах" to "Random / multiple cylinder misfire detected"),
        "P0301" to ("Пропуски воспламенения в цилиндре 1" to "Cylinder 1 misfire detected"),
        "P0302" to ("Пропуски воспламенения в цилиндре 2" to "Cylinder 2 misfire detected"),
        "P0303" to ("Пропуски воспламенения в цилиндре 3" to "Cylinder 3 misfire detected"),
        "P0304" to ("Пропуски воспламенения в цилиндре 4" to "Cylinder 4 misfire detected"),
        "P0305" to ("Пропуски воспламенения в цилиндре 5" to "Cylinder 5 misfire detected"),
        "P0306" to ("Пропуски воспламенения в цилиндре 6" to "Cylinder 6 misfire detected"),
        "P0307" to ("Пропуски воспламенения в цилиндре 7" to "Cylinder 7 misfire detected"),
        "P0308" to ("Пропуски воспламенения в цилиндре 8" to "Cylinder 8 misfire detected"),
        "P0313" to ("Пропуски воспламенения при низком уровне топлива" to "Misfire detected with low fuel level"),
        "P0315" to ("Не записана характеристика коленвала (не выполнена адаптация)" to "Crankshaft position system variation not learned")
    )

    private fun oxygenSensors(): Map<String, Pair<String, String>> = mapOf(
        "P0030" to ("Цепь подогрева лямбда-зонда 1, банк 1" to "HO2S heater control circuit (bank 1 sensor 1)"),
        "P0031" to ("Низкий сигнал подогрева лямбда-зонда 1, банк 1" to "HO2S heater circuit low (bank 1 sensor 1)"),
        "P0032" to ("Высокий сигнал подогрева лямбда-зонда 1, банк 1" to "HO2S heater circuit high (bank 1 sensor 1)"),
        "P0036" to ("Цепь подогрева лямбда-зонда 2, банк 1" to "HO2S heater control circuit (bank 1 sensor 2)"),
        "P0037" to ("Низкий сигнал подогрева лямбда-зонда 2, банк 1" to "HO2S heater circuit low (bank 1 sensor 2)"),
        "P0038" to ("Высокий сигнал подогрева лямбда-зонда 2, банк 1" to "HO2S heater circuit high (bank 1 sensor 2)"),
        "P0040" to ("Перепутаны сигналы лямбда-зондов (банк 1 / банк 2, датчик 1)" to "O2 sensor signals swapped (bank 1 sensor 1 / bank 2 sensor 1)"),
        "P0050" to ("Цепь подогрева лямбда-зонда 1, банк 2" to "HO2S heater control circuit (bank 2 sensor 1)"),
        "P0130" to ("Цепь лямбда-зонда 1, банк 1" to "O2 sensor circuit (bank 1 sensor 1)"),
        "P0131" to ("Низкое напряжение лямбда-зонда 1, банк 1" to "O2 sensor circuit low voltage (bank 1 sensor 1)"),
        "P0132" to ("Высокое напряжение лямбда-зонда 1, банк 1" to "O2 sensor circuit high voltage (bank 1 sensor 1)"),
        "P0133" to ("Замедленная реакция лямбда-зонда 1, банк 1 (датчик состарился)" to "O2 sensor circuit slow response (bank 1 sensor 1)"),
        "P0134" to ("Нет сигнала лямбда-зонда 1, банк 1" to "O2 sensor circuit no activity detected (bank 1 sensor 1)"),
        "P0135" to ("Цепь подогревателя лямбда-зонда 1, банк 1" to "O2 sensor heater circuit (bank 1 sensor 1)"),
        "P0136" to ("Цепь лямбда-зонда 2, банк 1" to "O2 sensor circuit (bank 1 sensor 2)"),
        "P0137" to ("Низкое напряжение лямбда-зонда 2, банк 1" to "O2 sensor circuit low voltage (bank 1 sensor 2)"),
        "P0138" to ("Высокое напряжение лямбда-зонда 2, банк 1" to "O2 sensor circuit high voltage (bank 1 sensor 2)"),
        "P0139" to ("Замедленная реакция лямбда-зонда 2, банк 1" to "O2 sensor circuit slow response (bank 1 sensor 2)"),
        "P0140" to ("Нет сигнала лямбда-зонда 2, банк 1" to "O2 sensor circuit no activity detected (bank 1 sensor 2)"),
        "P0141" to ("Цепь подогревателя лямбда-зонда 2, банк 1" to "O2 sensor heater circuit (bank 1 sensor 2)"),
        "P0150" to ("Цепь лямбда-зонда 1, банк 2" to "O2 sensor circuit (bank 2 sensor 1)"),
        "P0151" to ("Низкое напряжение лямбда-зонда 1, банк 2" to "O2 sensor circuit low voltage (bank 2 sensor 1)"),
        "P0152" to ("Высокое напряжение лямбда-зонда 1, банк 2" to "O2 sensor circuit high voltage (bank 2 sensor 1)"),
        "P0153" to ("Замедленная реакция лямбда-зонда 1, банк 2" to "O2 sensor circuit slow response (bank 2 sensor 1)"),
        "P0155" to ("Цепь подогревателя лямбда-зонда 1, банк 2" to "O2 sensor heater circuit (bank 2 sensor 1)"),
        "P0156" to ("Цепь лямбда-зонда 2, банк 2" to "O2 sensor circuit (bank 2 sensor 2)"),
        "P0157" to ("Низкое напряжение лямбда-зонда 2, банк 2" to "O2 sensor circuit low voltage (bank 2 sensor 2)"),
        "P0158" to ("Высокое напряжение лямбда-зонда 2, банк 2" to "O2 sensor circuit high voltage (bank 2 sensor 2)"),
        "P0161" to ("Цепь подогревателя лямбда-зонда 2, банк 2" to "O2 sensor heater circuit (bank 2 sensor 2)")
    )

    private fun ignitionAndMisfire(): Map<String, Pair<String, String>> = mapOf(
        "P0316" to ("Пропуски воспламенения сразу после запуска" to "Misfire detected on startup"),
        "P0320" to ("Цепь датчика оборотов системы зажигания" to "Ignition engine speed input circuit"),
        "P0325" to ("Цепь датчика детонации 1, банк 1" to "Knock sensor 1 circuit (bank 1)"),
        "P0326" to ("Сигнал датчика детонации 1 вне диапазона, банк 1" to "Knock sensor 1 range / performance (bank 1)"),
        "P0327" to ("Низкий сигнал датчика детонации 1, банк 1" to "Knock sensor 1 circuit low (bank 1)"),
        "P0328" to ("Высокий сигнал датчика детонации 1, банк 1" to "Knock sensor 1 circuit high (bank 1)"),
        "P0330" to ("Цепь датчика детонации 2, банк 2" to "Knock sensor 2 circuit (bank 2)"),
        "P0335" to ("Цепь датчика положения коленвала A" to "Crankshaft position sensor A circuit"),
        "P0336" to ("Сигнал датчика коленвала A вне диапазона" to "Crankshaft position sensor A range / performance"),
        "P0337" to ("Низкий сигнал датчика коленвала A" to "Crankshaft position sensor A circuit low"),
        "P0338" to ("Высокий сигнал датчика коленвала A" to "Crankshaft position sensor A circuit high"),
        "P0339" to ("Пропадающий сигнал датчика коленвала A" to "Crankshaft position sensor A circuit intermittent"),
        "P0340" to ("Цепь датчика положения распредвала A" to "Camshaft position sensor A circuit"),
        "P0341" to ("Сигнал датчика распредвала A вне диапазона" to "Camshaft position sensor A range / performance"),
        "P0342" to ("Низкий сигнал датчика распредвала A" to "Camshaft position sensor A circuit low"),
        "P0343" to ("Высокий сигнал датчика распредвала A" to "Camshaft position sensor A circuit high"),
        "P0344" to ("Пропадающий сигнал датчика распредвала A" to "Camshaft position sensor A circuit intermittent"),
        "P0345" to ("Цепь датчика положения распредвала A, банк 2" to "Camshaft position sensor A circuit (bank 2)"),
        "P0350" to ("Цепь катушки зажигания (первичная/вторичная)" to "Ignition coil primary / secondary circuit"),
        "P0351" to ("Цепь катушки зажигания A" to "Ignition coil A primary / secondary circuit"),
        "P0352" to ("Цепь катушки зажигания B" to "Ignition coil B primary / secondary circuit"),
        "P0353" to ("Цепь катушки зажигания C" to "Ignition coil C primary / secondary circuit"),
        "P0354" to ("Цепь катушки зажигания D" to "Ignition coil D primary / secondary circuit"),
        "P0355" to ("Цепь катушки зажигания E" to "Ignition coil E primary / secondary circuit"),
        "P0356" to ("Цепь катушки зажигания F" to "Ignition coil F primary / secondary circuit"),
        "P0363" to ("Пропуски воспламенения - подача топлива отключена" to "Misfire detected - fueling disabled"),
        "P0365" to ("Цепь датчика положения распредвала B, банк 1" to "Camshaft position sensor B circuit (bank 1)"),
        "P0380" to ("Цепь свечей накаливания A (дизель)" to "Glow plug / heater circuit A")
    )

    private fun emissions(): Map<String, Pair<String, String>> = mapOf(
        "P0400" to ("Расход в системе рециркуляции отработавших газов (EGR)" to "Exhaust gas recirculation flow"),
        "P0401" to ("Недостаточный поток EGR (закоксован канал или клапан)" to "Exhaust gas recirculation flow insufficient"),
        "P0402" to ("Избыточный поток EGR (клапан не закрывается)" to "Exhaust gas recirculation flow excessive"),
        "P0403" to ("Цепь управления клапаном EGR" to "Exhaust gas recirculation control circuit"),
        "P0404" to ("Работа EGR вне диапазона" to "Exhaust gas recirculation control circuit range / performance"),
        "P0405" to ("Низкий сигнал датчика EGR A" to "Exhaust gas recirculation sensor A circuit low"),
        "P0406" to ("Высокий сигнал датчика EGR A" to "Exhaust gas recirculation sensor A circuit high"),
        "P0410" to ("Система подачи дополнительного воздуха" to "Secondary air injection system"),
        "P0411" to ("Неверный поток в системе дополнительного воздуха" to "Secondary air injection system incorrect flow"),
        "P0412" to ("Цепь клапана системы дополнительного воздуха A" to "Secondary air injection system valve A circuit"),
        "P0420" to ("Эффективность катализатора ниже порога, банк 1" to "Catalyst system efficiency below threshold (bank 1)"),
        "P0421" to ("Эффективность прогревочного катализатора ниже порога, банк 1" to "Warm-up catalyst efficiency below threshold (bank 1)"),
        "P0422" to ("Эффективность основного катализатора ниже порога, банк 1" to "Main catalyst efficiency below threshold (bank 1)"),
        "P0430" to ("Эффективность катализатора ниже порога, банк 2" to "Catalyst system efficiency below threshold (bank 2)"),
        "P0431" to ("Эффективность прогревочного катализатора ниже порога, банк 2" to "Warm-up catalyst efficiency below threshold (bank 2)"),
        "P0440" to ("Система улавливания паров топлива (EVAP)" to "Evaporative emission control system"),
        "P0441" to ("Неверный поток продувки адсорбера" to "Evaporative emission system incorrect purge flow"),
        "P0442" to ("Небольшая утечка в системе EVAP" to "Evaporative emission system leak detected (small leak)"),
        "P0443" to ("Цепь клапана продувки адсорбера" to "Evaporative emission system purge control valve circuit"),
        "P0444" to ("Обрыв цепи клапана продувки адсорбера" to "Evaporative emission system purge control valve circuit open"),
        "P0445" to ("Замыкание цепи клапана продувки адсорбера" to "Evaporative emission system purge control valve circuit shorted"),
        "P0446" to ("Цепь клапана вентиляции адсорбера" to "Evaporative emission system vent control circuit"),
        "P0447" to ("Обрыв цепи клапана вентиляции адсорбера" to "Evaporative emission system vent control circuit open"),
        "P0448" to ("Замыкание цепи клапана вентиляции адсорбера" to "Evaporative emission system vent control circuit shorted"),
        "P0449" to ("Цепь клапана/соленоида вентиляции адсорбера" to "Evaporative emission system vent valve / solenoid circuit"),
        "P0451" to ("Сигнал датчика давления в системе EVAP вне диапазона" to "Evaporative emission system pressure sensor range / performance"),
        "P0452" to ("Низкий сигнал датчика давления EVAP" to "Evaporative emission system pressure sensor low input"),
        "P0453" to ("Высокий сигнал датчика давления EVAP" to "Evaporative emission system pressure sensor high input"),
        "P0455" to ("Большая утечка в системе EVAP (часто незакрытая крышка бака)" to "Evaporative emission system leak detected (gross leak)"),
        "P0456" to ("Очень малая утечка в системе EVAP" to "Evaporative emission system leak detected (very small leak)"),
        "P0457" to ("Крышка топливного бака не закрыта" to "Evaporative emission system leak detected (fuel cap loose / off)"),
        "P0460" to ("Цепь датчика уровня топлива" to "Fuel level sensor circuit"),
        "P0461" to ("Сигнал датчика уровня топлива вне диапазона" to "Fuel level sensor circuit range / performance"),
        "P0462" to ("Низкий сигнал датчика уровня топлива" to "Fuel level sensor circuit low input"),
        "P0463" to ("Высокий сигнал датчика уровня топлива" to "Fuel level sensor circuit high input"),
        "P0480" to ("Цепь управления вентилятором 1" to "Cooling fan 1 control circuit"),
        "P0481" to ("Цепь управления вентилятором 2" to "Cooling fan 2 control circuit"),
        "P0545" to ("Низкий сигнал датчика температуры выхлопа, банк 1 датчик 1" to "Exhaust gas temperature sensor circuit low (bank 1 sensor 1)")
    )

    private fun speedIdleAndOutputs(): Map<String, Pair<String, String>> = mapOf(
        "P0500" to ("Датчик скорости автомобиля" to "Vehicle speed sensor"),
        "P0501" to ("Сигнал датчика скорости вне диапазона" to "Vehicle speed sensor range / performance"),
        "P0502" to ("Низкий сигнал датчика скорости" to "Vehicle speed sensor circuit low input"),
        "P0503" to ("Пропадающий сигнал датчика скорости" to "Vehicle speed sensor intermittent / erratic"),
        "P0505" to ("Система управления холостым ходом" to "Idle air control system"),
        "P0506" to ("Обороты холостого хода ниже заданных" to "Idle air control system RPM lower than expected"),
        "P0507" to ("Обороты холостого хода выше заданных (подсос воздуха)" to "Idle air control system RPM higher than expected"),
        "P0510" to ("Выключатель закрытого положения дросселя" to "Closed throttle position switch"),
        "P0520" to ("Цепь датчика давления масла" to "Engine oil pressure sensor / switch circuit"),
        "P0521" to ("Сигнал датчика давления масла вне диапазона" to "Engine oil pressure sensor range / performance"),
        "P0522" to ("Низкое напряжение датчика давления масла" to "Engine oil pressure sensor low voltage"),
        "P0523" to ("Высокое напряжение датчика давления масла" to "Engine oil pressure sensor high voltage"),
        "P0530" to ("Цепь датчика давления хладагента кондиционера" to "A/C refrigerant pressure sensor circuit"),
        "P0532" to ("Низкий сигнал датчика давления хладагента" to "A/C refrigerant pressure sensor circuit low"),
        "P0533" to ("Высокий сигнал датчика давления хладагента" to "A/C refrigerant pressure sensor circuit high"),
        "P0560" to ("Напряжение бортовой сети" to "System voltage"),
        "P0562" to ("Низкое напряжение бортовой сети (генератор, АКБ)" to "System voltage low"),
        "P0563" to ("Высокое напряжение бортовой сети (реле-регулятор)" to "System voltage high"),
        "P0571" to ("Цепь выключателя педали тормоза A" to "Brake switch A circuit"),
        "P0600" to ("Линия последовательной связи между блоками" to "Serial communication link"),
        "P0601" to ("Ошибка контрольной суммы памяти блока управления" to "Internal control module memory checksum error"),
        "P0602" to ("Ошибка программирования блока управления" to "Control module programming error"),
        "P0603" to ("Ошибка энергонезависимой памяти блока (KAM)" to "Internal control module keep alive memory error"),
        "P0604" to ("Ошибка ОЗУ блока управления" to "Internal control module RAM error"),
        "P0605" to ("Ошибка ПЗУ блока управления" to "Internal control module ROM error"),
        "P0606" to ("Отказ процессора блока управления двигателем" to "ECM / PCM processor fault"),
        "P0607" to ("Работа блока управления вне нормы" to "Control module performance"),
        "P0620" to ("Цепь управления генератором" to "Generator control circuit"),
        "P0622" to ("Цепь обмотки возбуждения генератора F" to "Generator field F terminal circuit"),
        "P0627" to ("Обрыв цепи управления бензонасосом A" to "Fuel pump A control circuit open"),
        "P0628" to ("Низкий сигнал цепи управления бензонасосом A" to "Fuel pump A control circuit low"),
        "P0629" to ("Высокий сигнал цепи управления бензонасосом A" to "Fuel pump A control circuit high"),
        "P0645" to ("Цепь реле компрессора кондиционера" to "A/C clutch relay control circuit"),
        "P0650" to ("Цепь лампы Check Engine" to "Malfunction indicator lamp control circuit"),
        "P0655" to ("Цепь лампы перегрева двигателя" to "Engine hot lamp output control circuit")
    )

    private fun transmission(): Map<String, Pair<String, String>> = mapOf(
        "P0700" to ("Блок управления КПП сообщает о неисправности (ищите код в КПП)" to "Transmission control system (MIL request)"),
        "P0701" to ("Работа системы управления КПП вне диапазона" to "Transmission control system range / performance"),
        "P0702" to ("Электрическая неисправность системы управления КПП" to "Transmission control system electrical"),
        "P0703" to ("Цепь выключателя тормоза B / гидротрансформатора" to "Torque converter / brake switch B circuit"),
        "P0705" to ("Цепь датчика положения селектора (PRNDL)" to "Transmission range sensor circuit (PRNDL input)"),
        "P0706" to ("Сигнал датчика положения селектора вне диапазона" to "Transmission range sensor range / performance"),
        "P0710" to ("Цепь датчика температуры масла КПП" to "Transmission fluid temperature sensor circuit"),
        "P0711" to ("Сигнал датчика температуры масла КПП вне диапазона" to "Transmission fluid temperature sensor range / performance"),
        "P0712" to ("Низкий сигнал датчика температуры масла КПП" to "Transmission fluid temperature sensor circuit low"),
        "P0713" to ("Высокий сигнал датчика температуры масла КПП" to "Transmission fluid temperature sensor circuit high"),
        "P0715" to ("Цепь датчика оборотов входного вала / турбины" to "Input / turbine speed sensor circuit"),
        "P0716" to ("Сигнал датчика оборотов входного вала вне диапазона" to "Input / turbine speed sensor range / performance"),
        "P0717" to ("Нет сигнала датчика оборотов входного вала" to "Input / turbine speed sensor circuit no signal"),
        "P0720" to ("Цепь датчика оборотов выходного вала" to "Output speed sensor circuit"),
        "P0721" to ("Сигнал датчика оборотов выходного вала вне диапазона" to "Output speed sensor range / performance"),
        "P0722" to ("Нет сигнала датчика оборотов выходного вала" to "Output speed sensor circuit no signal"),
        "P0725" to ("Цепь сигнала оборотов двигателя (вход КПП)" to "Engine speed input circuit"),
        "P0730" to ("Неверное передаточное отношение" to "Incorrect gear ratio"),
        "P0731" to ("Неверное передаточное отношение 1-й передачи" to "Gear 1 incorrect ratio"),
        "P0732" to ("Неверное передаточное отношение 2-й передачи" to "Gear 2 incorrect ratio"),
        "P0733" to ("Неверное передаточное отношение 3-й передачи" to "Gear 3 incorrect ratio"),
        "P0734" to ("Неверное передаточное отношение 4-й передачи" to "Gear 4 incorrect ratio"),
        "P0735" to ("Неверное передаточное отношение 5-й передачи" to "Gear 5 incorrect ratio"),
        "P0740" to ("Цепь блокировки гидротрансформатора" to "Torque converter clutch circuit"),
        "P0741" to ("Блокировка гидротрансформатора не включается" to "Torque converter clutch circuit performance / stuck off"),
        "P0742" to ("Блокировка гидротрансформатора не отключается" to "Torque converter clutch circuit stuck on"),
        "P0743" to ("Электрическая неисправность блокировки гидротрансформатора" to "Torque converter clutch circuit electrical"),
        "P0745" to ("Соленоид регулировки давления A" to "Pressure control solenoid A"),
        "P0746" to ("Соленоид регулировки давления A не работает / залип выключенным" to "Pressure control solenoid A performance / stuck off"),
        "P0747" to ("Соленоид регулировки давления A залип включённым" to "Pressure control solenoid A stuck on"),
        "P0750" to ("Соленоид переключения A" to "Shift solenoid A"),
        "P0751" to ("Соленоид переключения A не работает / залип выключенным" to "Shift solenoid A performance / stuck off"),
        "P0752" to ("Соленоид переключения A залип включённым" to "Shift solenoid A stuck on"),
        "P0755" to ("Соленоид переключения B" to "Shift solenoid B"),
        "P0756" to ("Соленоид переключения B не работает / залип выключенным" to "Shift solenoid B performance / stuck off"),
        "P0760" to ("Соленоид переключения C" to "Shift solenoid C"),
        "P0765" to ("Соленоид переключения D" to "Shift solenoid D"),
        "P0770" to ("Соленоид переключения E" to "Shift solenoid E"),
        "P0780" to ("Неисправность переключения передач" to "Shift malfunction"),
        "P0781" to ("Неисправность переключения 1-2" to "1-2 shift malfunction"),
        "P0795" to ("Соленоид регулировки давления C" to "Pressure control solenoid C"),
        "P0801" to ("Цепь блокировки заднего хода" to "Reverse inhibit control circuit"),
        "P0820" to ("Датчик положения селектора по осям X-Y" to "Gear lever X-Y position sensor circuit"),
        "P0840" to ("Цепь датчика давления масла КПП A" to "Transmission fluid pressure sensor A circuit"),
        "P0850" to ("Вход выключателя нейтрали / парковки" to "Park / neutral switch input circuit"),
        "P0A7F" to ("Износ высоковольтной батареи гибрида" to "Hybrid battery pack deterioration"),
        "P0A80" to ("Требуется замена высоковольтной батареи гибрида" to "Replace hybrid battery pack")
    )

    private fun extraStandard(): Map<String, Pair<String, String>> = mapOf(
        "P2000" to ("Эффективность накопителя NOx ниже порога, банк 1" to "NOx trap efficiency below threshold (bank 1)"),
        "P2002" to ("Эффективность сажевого фильтра ниже порога, банк 1" to "Particulate filter efficiency below threshold (bank 1)"),
        "P2004" to ("Заслонки впускного коллектора залипли открытыми, банк 1" to "Intake manifold runner control stuck open (bank 1)"),
        "P2006" to ("Заслонки впускного коллектора залипли закрытыми, банк 1" to "Intake manifold runner control stuck closed (bank 1)"),
        "P2009" to ("Низкий сигнал цепи заслонок впускного коллектора, банк 1" to "Intake manifold runner control circuit low (bank 1)"),
        "P2010" to ("Высокий сигнал цепи заслонок впускного коллектора, банк 1" to "Intake manifold runner control circuit high (bank 1)"),
        "P2015" to ("Сигнал датчика положения заслонок впуска вне диапазона, банк 1" to "Intake manifold runner position sensor range / performance (bank 1)"),
        "P2080" to ("Сигнал датчика температуры выхлопа вне диапазона, банк 1 датчик 1" to "Exhaust gas temperature sensor range / performance (bank 1 sensor 1)"),
        "P2088" to ("Низкий сигнал привода распредвала A, банк 1" to "Camshaft position actuator A circuit low (bank 1)"),
        "P2096" to ("Смесь после катализатора слишком бедная, банк 1" to "Post catalyst fuel trim system too lean (bank 1)"),
        "P2097" to ("Смесь после катализатора слишком богатая, банк 1" to "Post catalyst fuel trim system too rich (bank 1)"),
        "P2100" to ("Цепь мотора привода дросселя" to "Throttle actuator control motor circuit"),
        "P2101" to ("Привод дросселя работает вне диапазона" to "Throttle actuator control motor range / performance"),
        "P2102" to ("Низкий сигнал цепи мотора привода дросселя" to "Throttle actuator control motor circuit low"),
        "P2103" to ("Высокий сигнал цепи мотора привода дросселя" to "Throttle actuator control motor circuit high"),
        "P2104" to ("Привод дросселя - принудительный холостой ход" to "Throttle actuator control system - forced idle"),
        "P2105" to ("Привод дросселя - принудительная остановка двигателя" to "Throttle actuator control system - forced engine shutdown"),
        "P2106" to ("Привод дросселя - принудительное ограничение мощности" to "Throttle actuator control system - forced limited power"),
        "P2108" to ("Неисправность модуля привода дросселя" to "Throttle actuator control module performance"),
        "P2109" to ("Нижний упор датчика положения дросселя A" to "Throttle position sensor A minimum stop performance"),
        "P2111" to ("Привод дросселя залип открытым" to "Throttle actuator control system - stuck open"),
        "P2112" to ("Привод дросселя залип закрытым" to "Throttle actuator control system - stuck closed"),
        "P2119" to ("Дроссельная заслонка вне диапазона" to "Throttle actuator control throttle body range / performance"),
        "P2122" to ("Низкий сигнал датчика педали акселератора D" to "Accelerator pedal position sensor D circuit low"),
        "P2123" to ("Высокий сигнал датчика педали акселератора D" to "Accelerator pedal position sensor D circuit high"),
        "P2127" to ("Низкий сигнал датчика педали акселератора E" to "Accelerator pedal position sensor E circuit low"),
        "P2128" to ("Высокий сигнал датчика педали акселератора E" to "Accelerator pedal position sensor E circuit high"),
        "P2135" to ("Рассогласование напряжений датчиков положения дросселя A и B" to "Throttle position sensor A / B voltage correlation"),
        "P2138" to ("Рассогласование напряжений датчиков педали D и E" to "Accelerator pedal position sensor D / E voltage correlation"),
        "P2146" to ("Обрыв питания группы форсунок A" to "Fuel injector group A supply voltage circuit open"),
        "P2178" to ("Слишком богатая смесь под нагрузкой, банк 1" to "System too rich off idle (bank 1)"),
        "P2179" to ("Слишком бедная смесь под нагрузкой, банк 1" to "System too lean off idle (bank 1)"),
        "P2187" to ("Слишком бедная смесь на холостом ходу, банк 1" to "System too lean at idle (bank 1)"),
        "P2188" to ("Слишком богатая смесь на холостом ходу, банк 1" to "System too rich at idle (bank 1)"),
        "P2195" to ("Сигнал лямбда-зонда завис на бедной смеси, банк 1 датчик 1" to "O2 sensor signal stuck lean (bank 1 sensor 1)"),
        "P2196" to ("Сигнал лямбда-зонда завис на богатой смеси, банк 1 датчик 1" to "O2 sensor signal stuck rich (bank 1 sensor 1)"),
        "P2200" to ("Цепь датчика NOx, банк 1" to "NOx sensor circuit (bank 1)"),
        "P2270" to ("Сигнал лямбда-зонда завис на бедной смеси, банк 1 датчик 2" to "O2 sensor signal stuck lean (bank 1 sensor 2)"),
        "P2271" to ("Сигнал лямбда-зонда завис на богатой смеси, банк 1 датчик 2" to "O2 sensor signal stuck rich (bank 1 sensor 2)"),
        "P2279" to ("Подсос воздуха во впускном тракте" to "Intake air system leak"),
        "P2297" to ("Сигнал лямбда-зонда вне диапазона при замедлении, банк 1 датчик 1" to "O2 sensor out of range during deceleration (bank 1 sensor 1)"),
        "P2299" to ("Несовместимые положения педалей тормоза и акселератора" to "Brake pedal position / accelerator pedal position incompatible"),
        "P2302" to ("Вторичная цепь катушки зажигания A - нет искры" to "Ignition coil A secondary circuit"),
        "P242F" to ("Сажевый фильтр забит золой" to "Particulate filter restriction - ash accumulation"),
        "P2452" to ("Цепь датчика перепада давления на сажевом фильтре" to "Particulate filter differential pressure sensor circuit"),
        "P2453" to ("Сигнал датчика перепада давления на сажевом фильтре вне диапазона" to "Particulate filter differential pressure sensor range / performance"),
        "P2459" to ("Слишком частая регенерация сажевого фильтра" to "Particulate filter regeneration frequency"),
        "P2463" to ("Сажевый фильтр забит сажей" to "Particulate filter restriction - soot accumulation"),
        "P2500" to ("Низкий сигнал лампы / клеммы L генератора" to "Generator lamp / L terminal circuit low"),
        "P3400" to ("Система отключения цилиндров, банк 1" to "Cylinder deactivation system (bank 1)")
    )

    private fun networkChassisBody(): Map<String, Pair<String, String>> = mapOf(
        "U0001" to ("Высокоскоростная шина CAN" to "High speed CAN communication bus"),
        "U0073" to ("Шина связи блоков управления A отключена" to "Control module communication bus A off"),
        "U0100" to ("Нет связи с блоком управления двигателем (ECM/PCM)" to "Lost communication with ECM / PCM A"),
        "U0101" to ("Нет связи с блоком управления КПП" to "Lost communication with TCM"),
        "U0121" to ("Нет связи с блоком ABS" to "Lost communication with ABS control module"),
        "U0140" to ("Нет связи с блоком кузовной электроники" to "Lost communication with body control module"),
        "U0155" to ("Нет связи с комбинацией приборов" to "Lost communication with instrument panel cluster"),
        "U0401" to ("Неверные данные от блока управления двигателем" to "Invalid data received from ECM / PCM A"),
        "U0402" to ("Неверные данные от блока управления КПП" to "Invalid data received from TCM"),
        "C0035" to ("Цепь датчика скорости левого переднего колеса" to "Left front wheel speed sensor circuit"),
        "C0040" to ("Цепь датчика скорости правого переднего колеса" to "Right front wheel speed sensor circuit"),
        "C0045" to ("Цепь датчика скорости левого заднего колеса" to "Left rear wheel speed sensor circuit"),
        "C0050" to ("Цепь датчика скорости правого заднего колеса" to "Right rear wheel speed sensor circuit"),
        "C0121" to ("Цепь реле клапанов ABS" to "Valve relay circuit"),
        "C0265" to ("Цепь реле мотора насоса ABS" to "EBCM motor relay circuit"),
        "B0001" to ("Цепь управления подушкой безопасности водителя" to "Driver airbag deployment control"),
        "B0004" to ("Цепь управления подушкой безопасности пассажира" to "Passenger airbag deployment control")
    )

    /** Семейства кодов: что значит номер, даже если самого кода нет в таблице. */
    private fun family(code: String): Pair<String, String>? {
        val letter = code.firstOrNull() ?: return null
        val n = code.drop(1)
        return when (letter) {
            'P' -> when {
                n.startsWith("0") || n.startsWith("2") -> when {
                    n.getOrNull(1) in listOf('0', '1') -> "Топливо и воздух: датчики впуска, смесь, лямбда-зонды" to "Fuel and air metering: intake sensors, mixture, O2 sensors"
                    n.getOrNull(1) == '2' -> "Топливо и воздух: форсунки и топливоподача" to "Fuel and air metering: injector circuits"
                    n.getOrNull(1) == '3' -> "Зажигание и пропуски воспламенения" to "Ignition system or misfire"
                    n.getOrNull(1) == '4' -> "Системы снижения токсичности: EGR, катализатор, адсорбер" to "Auxiliary emission controls: EGR, catalyst, EVAP"
                    n.getOrNull(1) == '5' -> "Холостой ход, скорость, вспомогательные входы" to "Vehicle speed, idle control and auxiliary inputs"
                    n.getOrNull(1) == '6' -> "Блок управления и его выходные цепи" to "Computer output circuits"
                    n.getOrNull(1) in listOf('7', '8', '9') -> "Коробка передач" to "Transmission"
                    n.getOrNull(1) == 'A' -> "Гибридный привод" to "Hybrid propulsion"
                    else -> "Двигатель и КПП" to "Powertrain"
                }
                n.startsWith("1") || n.startsWith("3") ->
                    "Код производителя: расшифровка зависит от марки" to "Manufacturer-specific code: meaning depends on the make"
                else -> null
            }
            'C' -> if (n.startsWith("1")) {
                "Код производителя (шасси)" to "Manufacturer-specific chassis code"
            } else {
                "Шасси: тормоза, ABS, подвеска, рулевое" to "Chassis: brakes, ABS, suspension, steering"
            }
            'B' -> if (n.startsWith("1")) {
                "Код производителя (кузов)" to "Manufacturer-specific body code"
            } else {
                "Кузов и салон: подушки, свет, климат" to "Body: airbags, lighting, climate"
            }
            'U' -> if (n.startsWith("1")) {
                "Код производителя (сеть)" to "Manufacturer-specific network code"
            } else {
                "Обмен данными между блоками управления" to "Network communication between control modules"
            }
            else -> null
        }
    }

    /** Коды, после которых ехать дальше дороже, чем не ехать. */
    private val CRITICAL = setOf(
        "P0217", "P0219", "P0300", "P0301", "P0302", "P0303", "P0304", "P0305", "P0306",
        "P0307", "P0308", "P0313", "P0363", "P0520", "P0521", "P0522", "P0562", "P0606",
        "P2105", "P2111", "P2112", "P0A80"
    )

    /** Коды, с которыми можно спокойно доехать и показать мастеру. */
    private val MINOR = setOf(
        "P0442", "P0455", "P0456", "P0457", "P0441", "P0446", "P0449", "P0451", "P0452",
        "P0453", "P0460", "P0461", "P0462", "P0463", "P0530", "P0532", "P0533", "P0645"
    )

    fun describe(code: String): String {
        val upper = code.uppercase()
        TABLE[upper]?.let { return ObdText.pick(it.first, it.second) }
        family(upper)?.let {
            return ObdText.pick(
                "${it.first}. Точной расшифровки этого кода в справочнике нет.",
                "${it.second}. No exact description for this code in the catalogue."
            )
        }
        return ObdText.pick("Неизвестный код", "Unknown code")
    }

    /** Короткое название группы - для заголовков и сортировки списка. */
    fun group(code: String): String {
        val f = family(code.uppercase()) ?: return ObdText.pick("Прочее", "Other")
        return ObdText.pick(f.first, f.second)
    }

    fun severity(code: String): DtcSeverity {
        val upper = code.uppercase()
        return when {
            upper in CRITICAL -> DtcSeverity.CRITICAL
            upper in MINOR -> DtcSeverity.INFO
            else -> DtcSeverity.WARNING
        }
    }

    /** Есть ли точная (не по семейству) расшифровка - показываем это в интерфейсе. */
    fun hasExactDescription(code: String): Boolean = TABLE.containsKey(code.uppercase())

    /** Сколько кодов в справочнике - показывается в "О программе". */
    val size: Int get() = TABLE.size
}
