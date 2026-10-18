package com.obdscanner.core

import com.obdscanner.core.pro.FeatureAccess
import com.obdscanner.core.pro.ProFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Граница между бесплатной версией и Pro.
 *
 * Проверяется не «работает ли оплата» (её тут нет и быть не должно), а сама
 * политика: что платно, что бесплатно и сколько плиток даёт бесплатная
 * версия. Это единственное место, где такое решение записано, и тест нужен
 * ровно затем, чтобы случайная правка не утащила за деньги то, что обещано
 * бесплатным.
 */
class FeatureAccessTest {

    @Test
    /** с Pro доступно всё, что вообще может быть платным. */
    fun proAllowsEverything() {
        ProFeature.entries.forEach { feature ->
            assertTrue("$feature доступна с Pro", FeatureAccess.PRO.allows(feature))
        }
        assertEquals(FeatureAccess.UNLIMITED_TILES, FeatureAccess.PRO.dashboardTileLimit)
        assertTrue(FeatureAccess.PRO.canAddTile(current = 93))
    }

    @Test
    /** без Pro платные возможности закрыты - все до одной. */
    fun freeLocksEveryPaidFeature() {
        ProFeature.entries.forEach { feature ->
            assertFalse("$feature закрыта без Pro", FeatureAccess.FREE.allows(feature))
        }
    }

    @Test
    /**
     * Бесплатная приборка остаётся приборкой.
     *
     * Четыре плитки - это уже температура, нагрузка, дроссель и напряжение;
     * меньше - и смотреть на экран незачем. Шесть оставляют запас и всё же
     * оставляют видимый смысл снимать ограничение.
     */
    fun freeDashboardStaysUsable() {
        assertEquals(6, FeatureAccess.FREE.dashboardTileLimit)
        assertTrue("четыре плитки - необходимый минимум", FeatureAccess.FREE.dashboardTileLimit >= 4)
        assertTrue(FeatureAccess.FREE.canAddTile(current = 5))
        assertFalse(FeatureAccess.FREE.canAddTile(current = 6))
        assertFalse(FeatureAccess.FREE.canAddTile(current = 9))
    }

    @Test
    /**
     * Архив: последняя запись бесплатна, история - платная.
     *
     * «Что было в прошлый раз» - это и есть то, ради чего архив смотрят, и
     * оно остаётся бесплатным. Платное начинается там, где сравнивают
     * несколько чтений: не сбрасывал ли кто-то ошибки перед продажей.
     */
    fun freeArchiveShowsTheLastReading() {
        assertEquals(1, FeatureAccess.FREE.dtcArchiveDepth)
        assertEquals(FeatureAccess.UNLIMITED_TILES, FeatureAccess.PRO.dtcArchiveDepth)
    }

    @Test
    /**
     * Каждая строчка списка платного на что-то влияет.
     *
     * Объявить возможность платной и не спросить про неё ни разу - это обещание
     * в README, которого нет в коде. Тест держит список и правила вместе.
     */
    fun everyPaidFeatureChangesSomething() {
        ProFeature.entries.forEach { feature ->
            assertTrue("$feature спрашивается у доступа", FeatureAccess.PRO.allows(feature))
            assertFalse("$feature закрыта без Pro", FeatureAccess.FREE.allows(feature))
        }
        // Две возможности выражены не запретом, а числом - и числа тоже разные.
        assertTrue(FeatureAccess.FREE.dashboardTileLimit < FeatureAccess.PRO.dashboardTileLimit)
        assertTrue(FeatureAccess.FREE.dtcArchiveDepth < FeatureAccess.PRO.dtcArchiveDepth)
    }

    @Test
    /**
     * Красные линии: это остаётся бесплатным навсегда.
     *
     * Тест выглядит странно - он проверяет, чего в перечислении НЕТ. Но
     * перечисление и есть вся платная стена, и единственный способ случайно
     * продать сброс ошибок - дописать туда строчку. Такую правку тест
     * останавливает и заодно объясняет, почему она неправильная.
     */
    fun thingsThatMustStayFree() {
        val paid = ProFeature.entries.map { it.name }
        listOf(
            // Прочитать и погасить ошибку - причина, по которой покупают адаптер.
            "DTC", "DTC_READ", "DTC_CLEAR", "CLEAR", "FREEZE_FRAME",
            // Показывать код и просить денег за его смысл - классика злых отзывов.
            "DESCRIPTIONS", "DTC_TEXT",
            // Готовность тестов: мы же сами и погасили человеку мониторы сбросом.
            "READINESS", "MONITORS",
            // Режим 06 и лямбда-зонды - лучшая бесплатная реклама приложения.
            "MODE06", "MONITOR_TESTS", "O2_SENSORS",
            // Связь и проверку адаптера нельзя проверить до покупки.
            "CONNECTION", "ADAPTER_CHECK", "DEMO",
            // Искусственное замедление приборов не продаётся ни при каких условиях.
            "POLL_RATE", "FAST_POLL", "REFRESH_RATE",
            // Доступность не продают.
            "LARGE_TEXT", "LOUPE",
            // Перестановка и размер плиток - способ разложить то, что уже
            // показано, а не отдельная возможность.
            "TILE_REORDER", "TILE_SIZE"
        ).forEach { forbidden ->
            assertFalse("«$forbidden» не может стать платным: это красная линия", forbidden in paid)
        }
    }

    @Test
    /** список платного - это и есть вся стена: она не пустая и не бесконечная. */
    fun theWallIsTheEnum() {
        assertEquals(9, ProFeature.entries.size)
        // Платное - только то, что приложение посчитало или собрало за человека.
        assertTrue(ProFeature.REPORT in ProFeature.entries)
        assertTrue(ProFeature.MODULE_SCAN in ProFeature.entries)
    }
}
