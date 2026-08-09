package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.components.RaAccountRow
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaAccountLogOutTest {

    private val nav = NavigationController()
    private val settingsViewModel: SettingsViewModel = mockk(relaxed = true)
    private val handler = testDialogInputHandler(
        nav = nav,
        ioScope = CoroutineScope(StandardTestDispatcher()),
        context = ApplicationProvider.getApplicationContext(),
        settingsViewModel = settingsViewModel,
    )

    // The view model's copy is the one the password row renders and the one Log In is armed from.
    // load() rebuilds the rows from the repository and never resets it, so clearing the stored
    // password alone left the previous user's still on screen.
    @Test fun `logging out clears the typed password too`() {
        nav.dialogState.value = DialogState.RAAccount(
            username = "bob",
            selectedIndex = RaAccountRow.entries.indexOf(RaAccountRow.LOG_OUT),
        )

        handler.onConfirm()

        verify { settingsViewModel.raPassword = "" }
        assertEquals(DialogState.None, nav.dialogState.value)
    }
}
