package com.obdscanner.app

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.obdscanner.app.data.AppSettings
import com.obdscanner.app.pro.ProAccess
import com.obdscanner.core.obd.Pids
import com.obdscanner.core.pro.FeatureAccess
import com.obdscanner.core.pro.ProFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Граница между бесплатной версией и Pro - на экране.
 *
 * Сама политика (что платно) проверена отдельно и без Android:
 * [com.obdscanner.core.FeatureAccessTest]. Здесь - то, что к ней ведёт:
 * платный раздел остаётся на месте и помечен, по нажатию объясняет себя, а
 * бесплатный открывается как открывался. И главное - что платное действие не
 * выполнится, даже если до кнопки всё-таки дошли: экранов много, забыть один
 * легко, и на этот случай есть вторая проверка в самой ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class ProGateTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    /**
     * Чистые настройки на каждый тест.
     *
     * Robolectric держит их в одном каталоге на весь класс, и переключатель
     * «показать без Pro», поставленный соседним тестом, утекал бы сюда - в
     * том числе в тот тест, который как раз проверяет значение по умолчанию.
     */
    fun clearSettings() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("obd_scanner", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    /**
     * И убрать за собой.
     *
     * Настройки Robolectric переживают не только соседний тест, но и соседний
     * класс: без этого приборка, перенастроенная здесь ради проверки лимита,
     * доставалась бы следующему тесту - и он падал бы на том, что не нашёл
     * плитку, которую сам не трогал.
     */
    fun restoreSettings() {
        clearSettings()
    }

    private fun viewModel() = ViewModelProvider(compose.activity)[ObdViewModel::class.java]

    @Test
    /**
     * Пока продавать негде, Pro открыт всем.
     *
     * Это не недосмотр, а решение: отбирать функции у людей, которым негде
     * заплатить, - худший первый шаг. Тест закрепляет его, чтобы оно не
     * поменялось случайно вместе с подключением магазина.
     *
     * Проверяется на чистых настройках, а не через экран: приложение в тесте
     * поднимается раньше, чем метод с очисткой, и к моменту проверки успело
     * бы прочитать переключатель, оставленный соседним тестом.
     */
    fun proIsOpenWhileThereIsNowhereToPay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("obd_scanner", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(FeatureAccess.PRO, ProAccess(AppSettings(context)).access.value)
    }

    private val outDir = File("build/outputs/roborazzi").apply { mkdirs() }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage(File(outDir, "$name.png").path)
    }

    @Test
    fun freeVersionMarksPaidSectionsButKeepsFaultsFree() {
        val vm = viewModel()
        vm.setPreviewWithoutPro(true)
        compose.waitForIdle()
        assertEquals(FeatureAccess.FREE, vm.access.value)

        compose.onAllNodesWithText("Проверка").onLast().performClick()
        compose.waitForIdle()
        shot("60_free_diagnostics")

        // Платные разделы помечены, и их в меню ровно два.
        compose.onAllNodesWithText("PRO").assertCountEquals(2)

        // Нажатие на платный раздел открывает объяснение, а не раздел.
        compose.onNodeWithText("Полная проверка").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Это входит в Pro").assertExists()
        shot("61_free_pro_dialog")
        compose.onNodeWithText("Понятно").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("К разделам").assertDoesNotExist()

        // А ошибки - бесплатны, это красная линия: человек купил адаптер,
        // чтобы их прочитать и погасить.
        compose.onAllNodesWithText("Ошибки").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("К разделам").assertExists()
    }

    @Test
    /** вторая линия обороны: платное действие не выполнится и в обход экрана. */
    fun paidActionsRefuseThemselvesWithoutPro() {
        val vm = viewModel()
        vm.setPreviewWithoutPro(true)
        compose.waitForIdle()

        assertFalse(vm.access.value.allows(ProFeature.REPORT))
        vm.runQuickCheck()
        assertFalse("полная проверка не запустилась", vm.quickCheck.value.running)

        assertFalse(vm.access.value.allows(ProFeature.TRIP))
        vm.startRecording()
        assertFalse("запись поездки не началась", vm.trip.value.recording)

        assertFalse(vm.access.value.allows(ProFeature.MODULE_SCAN))
        vm.scanModules()
        assertFalse("поиск блоков не начался", vm.modules.value.running)
    }

    @Test
    /** бесплатная приборка ограничена по числу плиток, но убрать плитку можно всегда. */
    fun freeDashboardKeepsItsTileLimit() {
        val vm = viewModel()
        vm.setPreviewWithoutPro(true)
        compose.waitForIdle()

        val limit = vm.access.value.dashboardTileLimit
        assertEquals(6, limit)

        // Набираем ровно предел.
        val wanted = Pids.ALL.map { it.id }.filter { it !in Pids.STATIC }.take(limit)
        val current = vm.dashboardPids.value.toList()
        current.forEach { if (it !in wanted) vm.toggleDashboardPid(it) }
        wanted.forEach { if (it !in vm.dashboardPids.value) vm.toggleDashboardPid(it) }
        assertEquals(limit, vm.dashboardPids.value.size)

        // Сверх предела не добавляется.
        val extra = Pids.ALL.map { it.id }.first { it !in vm.dashboardPids.value && it !in Pids.STATIC }
        vm.toggleDashboardPid(extra)
        assertEquals("плитка сверх предела не добавилась", limit, vm.dashboardPids.value.size)

        // А убрать - можно: иначе приборку не перенастроить вовсе.
        val first = vm.dashboardPids.value.first()
        vm.toggleDashboardPid(first)
        assertEquals(limit - 1, vm.dashboardPids.value.size)

        // И теперь на освободившееся место встаёт другая.
        vm.toggleDashboardPid(extra)
        assertTrue(extra in vm.dashboardPids.value)

        // С Pro ограничения нет.
        vm.setPreviewWithoutPro(false)
        assertEquals(FeatureAccess.UNLIMITED_TILES, vm.access.value.dashboardTileLimit)
    }
}
