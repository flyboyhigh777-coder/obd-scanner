package com.obdscanner.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.obdscanner.app.ui.loupeGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Жест лупы.
 *
 * Саму лупу тест увидеть не может: её рисует система в своём окне поверх
 * приложения, на снимке экрана её нет, а в тестовой среде она и вовсе не
 * заводится - нет настоящей поверхности. Зато можно проверить то, что
 * ломается на самом деле: кто получает нажатие первым.
 *
 * Главное здесь - что лупа забирает жест себе. Кнопка под пальцем не должна
 * сработать, когда палец отпустят: иначе разглядывание кнопки "Сохранить PDF"
 * заканчивалось бы сохранением PDF. И наоборот - обычное короткое касание
 * обязано работать как работало.
 *
 * Куда встаёт само стекло - отдельно и без Robolectric ([LoupePlacementTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class LoupeTest {

    @get:Rule
    val compose = createComposeRule()

    private val points = mutableListOf<Offset>()
    private var clicks = 0

    private fun content(loupeEnabled: Boolean = true) {
        compose.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .loupeGesture(enabled = { loupeEnabled }) { points += it }
            ) {
                Button(onClick = { clicks++ }) { Text("Сохранить PDF") }
            }
        }
    }

    @Test
    fun longPressOpensLoupeAndEatsTheClick() {
        content()
        compose.onNodeWithText("Сохранить PDF").performTouchInput {
            down(center)
            // Дольше порога долгого нажатия - это лупа, а не касание.
            advanceEventTime(900)
            moveTo(center + Offset(0f, -40f))
            advanceEventTime(60)
            up()
        }
        compose.waitForIdle()

        assertEquals("кнопка под лупой не нажалась", 0, clicks)
        assertTrue("лупа открылась", points.isNotEmpty())
        // Лупа ехала за пальцем, а в конце закрылась.
        assertTrue("лупа следила за пальцем", points.count { it != Offset.Unspecified } >= 2)
        assertEquals("лупа закрылась", Offset.Unspecified, points.last())
    }

    @Test
    fun shortTapStillClicks() {
        content()
        compose.onNodeWithText("Сохранить PDF").performClick()
        compose.waitForIdle()

        assertEquals("обычное касание работает", 1, clicks)
        assertTrue("лупа не открывалась", points.isEmpty())
    }

    @Test
    /** там, где лупа выключена (приборка), долгое нажатие остаётся чужим. */
    fun disabledLoupeKeepsOutOfTheWay() {
        content(loupeEnabled = false)
        compose.onNodeWithText("Сохранить PDF").performTouchInput {
            down(center)
            advanceEventTime(900)
            up()
        }
        compose.waitForIdle()

        assertTrue("лупа не открывалась", points.isEmpty())
        // Кнопка при этом сработала сама - жест ей никто не мешал.
        assertEquals(1, clicks)
    }
}
