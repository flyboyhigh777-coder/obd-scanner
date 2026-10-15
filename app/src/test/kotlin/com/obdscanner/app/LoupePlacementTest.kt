package com.obdscanner.app

import com.obdscanner.app.ui.LoupePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Куда встаёт лупа относительно пальца.
 *
 * Обычный JUnit: это чистая арифметика краёв, и проверять её, тыкая пальцем в
 * телефон, - самый долгий из возможных способов.
 */
class LoupePlacementTest {

    private val width = 1000f
    private val height = 2000f
    private val lensWidth = 300f
    private val lensHeight = 100f
    private val gap = 40f

    private fun at(x: Float, y: Float) = LoupePlacement.lensCenter(
        sourceX = x,
        sourceY = y,
        areaWidth = width,
        areaHeight = height,
        lensWidth = lensWidth,
        lensHeight = lensHeight,
        gap = gap
    )

    @Test
    /** по умолчанию лупа висит над пальцем - он не закрывает то, что читают. */
    fun sitsAboveTheFinger() {
        val center = at(500f, 1000f)
        assertEquals(500f, center.x, 0.01f)
        assertEquals(1000f - gap - lensHeight / 2f, center.y, 0.01f)
        // И целиком над пальцем, а не наезжая на него.
        assertTrue("нижний край выше пальца", center.y + lensHeight / 2f <= 1000f)
    }

    @Test
    /** у верхнего края над пальцем места нет - лупа переезжает под него. */
    fun flipsBelowAtTheTop() {
        val center = at(500f, 60f)
        assertTrue("лупа ушла под палец", center.y > 60f)
        assertTrue("верхний край на экране", center.y - lensHeight / 2f >= 0f)
    }

    @Test
    /** у боковых краёв лупа упирается, а не уезжает за экран половиной. */
    fun staysInsideHorizontally() {
        assertEquals(lensWidth / 2f, at(0f, 1000f).x, 0.01f)
        assertEquals(width - lensWidth / 2f, at(width, 1000f).x, 0.01f)
    }

    @Test
    /** у нижнего края лупа не вылезает снизу. */
    fun staysInsideAtTheBottom() {
        // Палец у самого низа и у самого верха одновременно - узкая область,
        // где лупе тесно с обеих сторон.
        val center = LoupePlacement.lensCenter(
            sourceX = 500f,
            sourceY = 30f,
            areaWidth = width,
            areaHeight = 120f,
            lensWidth = lensWidth,
            lensHeight = lensHeight,
            gap = gap
        )
        assertTrue("нижний край не ниже области", center.y + lensHeight / 2f <= 120f + 0.01f)
        assertTrue("верхний край на экране", center.y - lensHeight / 2f >= -0.01f)
    }

    @Test
    /** размер стекла - от экрана, но не во всю его ширину. */
    fun lensGrowsWithTheScreenButNotToItsEdges() {
        // Телефон 411 x 891 dp: заметно больше прежних 280 x 104, но по
        // краям остаётся страница - иначе непонятно, где ты на ней.
        assertEquals(329f, LoupePlacement.lensWidth(411f), 1f)
        assertEquals(132f, LoupePlacement.lensHeight(411f, 891f), 1f)
        assertTrue("не во всю ширину", LoupePlacement.lensWidth(411f) < 411f * 0.85f)

        // Магнитола 1000 x 600 dp: шире предела стекло не растёт.
        assertEquals(380f, LoupePlacement.lensWidth(1000f), 1f)
        // И не сплющивается на лежачем экране: высота считается от ширины.
        assertEquals(152f, LoupePlacement.lensHeight(1000f, 600f), 1f)

        // Совсем узкая и низкая область - стекло не больше её самой.
        assertTrue("не шире экрана", LoupePlacement.lensWidth(120f) <= 120f)
        assertTrue("не выше экрана", LoupePlacement.lensHeight(400f, 60f) <= 60f)
    }

    @Test
    /**
     * До первого замера экрана размер всё равно положительный.
     *
     * Нулевое стекло система не принимает: Magnifier.Builder.setSize роняет
     * приложение на "Width should be positive". А нулевой размер экрана - не
     * выдумка, это обычный первый кадр, до того как разметка померила себя.
     */
    fun lensIsNeverEmptyBeforeTheFirstLayout() {
        assertTrue("ширина до замера", LoupePlacement.lensWidth(0f) > 0f)
        assertTrue("высота до замера", LoupePlacement.lensHeight(0f, 0f) > 0f)
    }

    @Test
    /** лупа шире экрана - ставим по центру, а не прижимаем к краю. */
    fun centersWhenWiderThanTheScreen() {
        val center = LoupePlacement.lensCenter(
            sourceX = 10f,
            sourceY = 1000f,
            areaWidth = 200f,
            areaHeight = height,
            lensWidth = lensWidth,
            lensHeight = lensHeight,
            gap = gap
        )
        assertEquals(100f, center.x, 0.01f)
    }
}
