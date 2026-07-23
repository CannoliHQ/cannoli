package dev.cannoli.scorza.input.screen

import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameListSelectReleaseTest {

    private lateinit var nav: NavigationController
    private lateinit var glvm: GameListViewModel
    private lateinit var handler: GameListInputHandler

    @Before fun setup() {
        nav = NavigationController()
        glvm = mockk(relaxed = true)
        handler = GameListInputHandler(
            nav = nav,
            ioScope = mockk(relaxed = true),
            settings = mockk<SettingsRepository>(relaxed = true),
            gameListViewModel = glvm,
            launcherActions = mockk<LauncherActions>(relaxed = true),
        )
        every { glvm.isMultiSelectMode() } returns false
        every { glvm.isReorderMode() } returns false
        every { glvm.state } returns MutableStateFlow(GameListViewModel.State(isCollection = true))
    }

    // A dialog consumes the Select press but not the release, so the handler can see a release
    // whose press it never got. Acting on it silently reorders the list behind the dialog.
    @Test fun select_release_without_a_press_does_not_enter_reorder() {
        handler.onSelectUp()
        verify(exactly = 0) { glvm.enterReorderMode() }
    }

    @Test fun select_press_then_release_still_enters_reorder() {
        handler.onSelect()
        handler.onSelectUp()
        verify { glvm.enterReorderMode() }
    }

    @Test fun release_still_clears_press_state_so_the_next_tap_works() {
        handler.onSelect()
        handler.onSelectUp()
        handler.onSelect()
        handler.onSelectUp()
        verify(exactly = 2) { glvm.enterReorderMode() }
    }
}
