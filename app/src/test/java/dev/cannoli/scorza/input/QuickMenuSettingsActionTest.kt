package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.quickmenu.QuickMenuRow
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickMenuSettingsActionTest {

    private lateinit var nav: NavigationController
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var systemListViewModel: SystemListViewModel
    private lateinit var handler: DialogInputHandler

    @Before fun setup() {
        nav = NavigationController()
        settingsViewModel = mockk(relaxed = true)
        systemListViewModel = mockk(relaxed = true)
        handler = testDialogInputHandler(
            nav = nav,
            ioScope = CoroutineScope(StandardTestDispatcher()),
            context = ApplicationProvider.getApplicationContext(),
            settingsViewModel = settingsViewModel,
            systemListViewModel = systemListViewModel,
        )
    }

    private fun openSettingsRow() {
        val rows = QuickMenuRow.visibleRows(rommPaired = false, kitchenRunning = false)
        nav.dialogState.value = DialogState.QuickMenu(
            rows = rows,
            kitchenRunning = false,
            selectedIndex = rows.indexOf(QuickMenuRow.SETTINGS),
        )
        handler.onConfirm()
    }

    @Test fun settings_row_closes_the_dialog_and_pushes_settings() {
        openSettingsRow()
        assertEquals(DialogState.None, nav.dialogState.value)
        assertEquals(LauncherScreen.Settings, nav.currentScreen)
        verify { settingsViewModel.load() }
    }

    @Test fun position_is_saved_only_when_the_system_list_is_showing() {
        openSettingsRow()
        verify(exactly = 1) { systemListViewModel.savePosition() }
    }

    @Test fun position_is_not_saved_from_another_screen() {
        nav.screenStack.add(LauncherScreen.GameList)
        openSettingsRow()
        verify(exactly = 0) { systemListViewModel.savePosition() }
        assertEquals(LauncherScreen.Settings, nav.currentScreen)
    }

    // Otherwise the row would push a second Settings screen on top of the one already open.
    @Test fun menu_does_not_open_the_quick_menu_on_the_settings_screen() {
        nav.screenStack.add(LauncherScreen.Settings)
        assertFalse(handler.onMenu())
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun menu_still_opens_the_quick_menu_on_the_system_list() {
        assertTrue(handler.onMenu())
    }
}
