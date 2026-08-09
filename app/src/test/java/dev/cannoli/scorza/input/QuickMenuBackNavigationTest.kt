package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
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
        settingsViewModel = SettingsViewModel(
            settings = mockk(relaxed = true),
            appFonts = mockk(relaxed = true),
            context = ApplicationProvider.getApplicationContext(),
            rommStore = mockk(relaxed = true),
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

    @Test fun the_debug_row_marks_the_settings_screen_with_the_row_to_return_to() {
        confirmQuickRow(QuickMenuRow.DEBUG)
        assertEquals(LauncherScreen.Settings(QuickMenuRow.DEBUG), nav.currentScreen)
    }

    @Test fun the_settings_row_leaves_the_screen_unmarked() {
        confirmQuickRow(QuickMenuRow.SETTINGS)
        assertEquals(LauncherScreen.Settings(), nav.currentScreen)
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

    @Test fun back_from_a_normally_entered_category_still_goes_to_the_top_level_list() = runTest(dispatcher) {
        confirmQuickRow(QuickMenuRow.SETTINGS)
        settingsViewModel.enterCategory()
        assertEquals("general", settingsViewModel.state.value.activeCategory)

        settingsHandler.onBack()
        advanceUntilIdle()
        assertEquals(DialogState.None, nav.dialogState.value)
        assertEquals(LauncherScreen.Settings(), nav.currentScreen)
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
}
