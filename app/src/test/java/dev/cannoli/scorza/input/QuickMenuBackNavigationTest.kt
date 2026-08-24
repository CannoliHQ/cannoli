package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.quickmenu.QuickMenuRow
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickMenuBackNavigationTest {

    private lateinit var nav: NavigationController
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var dialogHandler: DialogInputHandler
    private lateinit var settingsHandler: dev.cannoli.scorza.input.screen.SettingsInputHandler

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
        dialogHandler = testDialogInputHandler(
            nav = nav,
            ioScope = CoroutineScope(dispatcher),
            context = ApplicationProvider.getApplicationContext(),
            settingsViewModel = settingsViewModel,
        )
        settingsHandler = testSettingsInputHandler(
            nav = nav,
            ioScope = CoroutineScope(dispatcher),
            context = ApplicationProvider.getApplicationContext(),
            settingsViewModel = settingsViewModel,
            dialogInputHandler = dialogHandler,
        )
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun confirmQuickRow(row: QuickMenuRow) {
        val rows = QuickMenuRow.visibleRows(rommPaired = false, kitchenRunning = false, debugBuild = true)
        nav.dialogState.value = DialogState.QuickMenu(
            rows = rows,
            kitchenRunning = false,
            selectedIndex = rows.indexOf(row),
        )
        dialogHandler.onConfirm()
    }

    @Test fun the_debug_row_marks_the_settings_screen_with_the_row_and_category_to_return_from() {
        confirmQuickRow(QuickMenuRow.DEBUG)
        assertEquals(LauncherScreen.Settings(QuickMenuRow.DEBUG, "debug"), nav.currentScreen)
    }

    @Test fun the_settings_row_marks_the_screen_with_no_category() {
        confirmQuickRow(QuickMenuRow.SETTINGS)
        assertEquals(LauncherScreen.Settings(QuickMenuRow.SETTINGS), nav.currentScreen)
    }

    @Test fun back_from_the_settings_list_returns_to_the_quick_menu_on_the_settings_row() = runTest(dispatcher) {
        confirmQuickRow(QuickMenuRow.SETTINGS)
        settingsHandler.onBack()
        advanceUntilIdle()
        val ds = nav.dialogState.value
        assertTrue(ds is DialogState.QuickMenu)
        ds as DialogState.QuickMenu
        assertEquals(QuickMenuRow.SETTINGS, ds.rows.getOrNull(ds.selectedIndex))
        assertEquals(LauncherScreen.SystemList, nav.currentScreen)
    }

    @Test fun back_from_the_debug_rows_returns_to_the_quick_menu_on_the_debug_row() = runTest(dispatcher) {
        confirmQuickRow(QuickMenuRow.DEBUG)
        settingsHandler.onBack()
        advanceUntilIdle()
        val ds = nav.dialogState.value
        assertTrue(ds is DialogState.QuickMenu)
        ds as DialogState.QuickMenu
        assertEquals(QuickMenuRow.DEBUG, ds.rows.getOrNull(ds.selectedIndex))
        assertEquals(LauncherScreen.SystemList, nav.currentScreen)
        assertNull(settingsViewModel.state.value.activeCategory)
    }

    // Unwinding before the menu is built flashes the screen underneath for the frames in between.
    @Test fun the_settings_screen_stays_up_until_the_quick_menu_is_ready() = runTest(dispatcher) {
        confirmQuickRow(QuickMenuRow.SETTINGS)
        settingsHandler.onBack()
        assertEquals(LauncherScreen.Settings(QuickMenuRow.SETTINGS), nav.currentScreen)
        assertEquals(DialogState.None, nav.dialogState.value)

        advanceUntilIdle()
        assertTrue(nav.dialogState.value is DialogState.QuickMenu)
        assertEquals(LauncherScreen.SystemList, nav.currentScreen)
    }

    @Test fun the_debug_category_stays_up_until_the_quick_menu_is_ready() = runTest(dispatcher) {
        confirmQuickRow(QuickMenuRow.DEBUG)
        settingsHandler.onBack()
        assertEquals("debug", settingsViewModel.state.value.activeCategory)
        assertEquals(DialogState.None, nav.dialogState.value)

        advanceUntilIdle()
        assertTrue(nav.dialogState.value is DialogState.QuickMenu)
        assertNull(settingsViewModel.state.value.activeCategory)
    }

    @Test fun back_from_a_normally_entered_category_still_goes_to_the_top_level_list() = runTest(dispatcher) {
        confirmQuickRow(QuickMenuRow.SETTINGS)
        settingsViewModel.enterCategory()
        assertEquals("general", settingsViewModel.state.value.activeCategory)

        settingsHandler.onBack()
        advanceUntilIdle()
        assertEquals(DialogState.None, nav.dialogState.value)
        assertEquals(LauncherScreen.Settings(QuickMenuRow.SETTINGS), nav.currentScreen)
        assertNull(settingsViewModel.state.value.activeCategory)
    }

    @Test fun back_from_credits_rebuilds_the_about_dialog_it_came_from() {
        val credits = LauncherScreen.Credits(fromQuickMenu = true)
        nav.screenStack.add(credits)
        creditsBack(nav, credits)
        assertEquals(DialogState.About(fromQuickMenu = true), nav.dialogState.value)
        assertEquals(listOf(LauncherScreen.SystemList), nav.screenStack.toList())
    }

    @Test fun back_from_credits_keeps_a_settings_era_about_unflagged() {
        val credits = LauncherScreen.Credits()
        nav.screenStack.add(credits)
        creditsBack(nav, credits)
        assertEquals(DialogState.About(fromQuickMenu = false), nav.dialogState.value)
        assertEquals(listOf(LauncherScreen.SystemList), nav.screenStack.toList())
    }

    @Test fun credits_carries_the_flag_from_the_about_dialog_that_pushed_it() {
        nav.dialogState.value = DialogState.About(fromQuickMenu = true)
        dialogHandler.onNorth()
        assertEquals(LauncherScreen.Credits(fromQuickMenu = true), nav.currentScreen)
    }

    @Test fun stopping_the_kitchen_returns_to_the_quick_menu_it_was_opened_from() = runTest(dispatcher) {
        nav.dialogState.value = DialogState.Kitchen(
            urls = listOf("http://10.0.0.2:1091"),
            pin = "1234",
            fromQuickMenu = true,
        )
        dialogHandler.onNorth()
        advanceUntilIdle()
        val ds = nav.dialogState.value
        assertTrue(ds is DialogState.QuickMenu)
        ds as DialogState.QuickMenu
        assertEquals(QuickMenuRow.KITCHEN, ds.rows.getOrNull(ds.selectedIndex))
        assertFalse(ds.kitchenRunning)
    }

    @Test fun stopping_the_kitchen_opened_off_the_system_list_closes_the_dialog() = runTest(dispatcher) {
        nav.dialogState.value = DialogState.Kitchen(
            urls = listOf("http://10.0.0.2:1091"),
            pin = "1234",
        )
        dialogHandler.onNorth()
        advanceUntilIdle()
        assertEquals(DialogState.None, nav.dialogState.value)
    }
}
