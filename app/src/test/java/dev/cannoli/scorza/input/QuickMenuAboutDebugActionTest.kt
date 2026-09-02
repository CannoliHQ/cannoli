package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.quickmenu.QuickMenuRow
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsCategory
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickMenuAboutDebugActionTest {

    private lateinit var nav: NavigationController
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var handler: DialogInputHandler

    // The quick-menu rebuild finishes on Dispatchers.Main, so Main has to be the same test
    // dispatcher for advanceUntilIdle() to carry the rebuild all the way to dialogState.
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        nav = NavigationController()
        val settings = mockk<SettingsRepository>(relaxed = true)
        settingsViewModel = SettingsViewModel(
            settings = settings,
            appFonts = mockk(relaxed = true),
            context = ApplicationProvider.getApplicationContext(),
            rommStore = mockk(relaxed = true),
            pathsProvider = CannoliPathsProvider(settings),
        )
        handler = testDialogInputHandler(
            nav = nav,
            ioScope = CoroutineScope(dispatcher),
            context = ApplicationProvider.getApplicationContext(),
            settingsViewModel = settingsViewModel,
        )
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun confirmRow(row: QuickMenuRow) {
        val rows = QuickMenuRow.visibleRows(rommPaired = false, kitchenRunning = false, devBuild = true)
        nav.dialogState.value = DialogState.QuickMenu(
            rows = rows,
            kitchenRunning = false,
            selectedIndex = rows.indexOf(row),
        )
        handler.onConfirm()
    }

    @Test fun about_row_opens_the_about_dialog_flagged_as_from_the_quick_menu() {
        confirmRow(QuickMenuRow.ABOUT)
        val ds = nav.dialogState.value
        assertTrue(ds is DialogState.About)
        assertTrue((ds as DialogState.About).fromQuickMenu)
    }

    @Test fun back_from_a_quick_menu_about_returns_to_the_quick_menu_on_the_about_row() = runTest(dispatcher) {
        nav.dialogState.value = DialogState.About(fromQuickMenu = true)
        handler.onBack()
        advanceUntilIdle()
        val ds = nav.dialogState.value
        assertTrue(ds is DialogState.QuickMenu)
        ds as DialogState.QuickMenu
        assertEquals(QuickMenuRow.ABOUT, ds.rows.getOrNull(ds.selectedIndex))
    }

    @Test fun back_from_a_plain_about_closes() {
        nav.dialogState.value = DialogState.About()
        handler.onBack()
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun debug_row_pushes_settings_already_inside_the_debug_category() {
        confirmRow(QuickMenuRow.DEBUG)
        assertEquals(DialogState.None, nav.dialogState.value)
        assertEquals(LauncherScreen.Settings(QuickMenuRow.DEBUG, SettingsCategory.DEBUG), nav.currentScreen)
        assertEquals(SettingsCategory.DEBUG, settingsViewModel.state.value.activeCategory)
        assertTrue(settingsViewModel.state.value.items.isNotEmpty())
    }

    @Test fun debug_is_not_a_top_level_settings_category() {
        settingsViewModel.load()
        assertTrue(settingsViewModel.state.value.categories.none { it.key == SettingsCategory.DEBUG })
    }
}
