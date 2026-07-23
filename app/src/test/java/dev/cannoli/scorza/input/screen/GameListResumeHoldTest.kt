package dev.cannoli.scorza.input.screen

import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameListResumeHoldTest {

    private lateinit var nav: NavigationController
    private lateinit var settings: SettingsRepository
    private lateinit var glvm: GameListViewModel
    private lateinit var actions: LauncherActions
    private lateinit var handler: GameListInputHandler

    private val rom = Rom(id = 1L, path = File("/roms/snes/Zelda.sfc"), platformTag = "snes", displayName = "Zelda")
    private val romItem = ListItem.RomItem(rom)

    @Before fun setup() {
        nav = NavigationController()
        settings = mockk(relaxed = true)
        glvm = mockk(relaxed = true)
        actions = mockk(relaxed = true)
        handler = GameListInputHandler(
            nav = nav,
            ioScope = mockk(relaxed = true),
            settings = settings,
            gameListViewModel = glvm,
            launcherActions = actions,
        )
        every { glvm.isMultiSelectMode() } returns false
        every { glvm.isReorderMode() } returns false
        every { glvm.getSelectedItem() } returns romItem
        nav.resumableGames = setOf(rom.path.absolutePath)
    }

    @Test fun confirm_tap_on_resumable_resumes_latest_when_swap_on() {
        every { settings.swapPlayResume } returns true
        every { glvm.state } returns MutableStateFlow(GameListViewModel.State())
        handler.onConfirm()              // arm (no launch yet)
        verify(exactly = 0) { actions.launchSelected(any(), any()) }
        handler.onConfirmUp()            // release before timeout = tap
        verify { actions.launchSelected(romItem, true) }
    }

    @Test fun confirm_hold_on_resumable_opens_picker_when_swap_on() {
        every { settings.swapPlayResume } returns true
        every { actions.buildSaveStatePicker(rom, any()) } returns
            LauncherScreen.SaveStatePicker(rom, "/x/base", List(11) { false }, 0, awaitConfirmRelease = true)
        handler.onConfirm()              // arm
        handler.resumeHoldRunnable.run() // simulate 400ms elapse
        assertTrue(nav.currentScreen is LauncherScreen.SaveStatePicker)
        verify(exactly = 0) { actions.launchSelected(any(), any()) }
        verify { actions.buildSaveStatePicker(rom, true) }
    }

    @Test fun held_confirm_pushes_picker_only_once() {
        every { settings.swapPlayResume } returns true
        every { actions.buildSaveStatePicker(rom, any()) } returns
            LauncherScreen.SaveStatePicker(rom, "/x/base", List(11) { false }, 0, awaitConfirmRelease = true)
        handler.onConfirm()              // arm
        handler.onConfirm()              // auto-repeat while held: must not re-arm
        val depthBefore = nav.screenStack.size
        handler.resumeHoldRunnable.run() // timer fires -> push once
        handler.resumeHoldRunnable.run() // any stale scheduled copy -> no-op
        assertEquals(depthBefore + 1, nav.screenStack.size)
    }

    @Test fun confirm_when_swap_off_is_immediate_play_not_armed() {
        every { settings.swapPlayResume } returns false
        every { glvm.state } returns MutableStateFlow(GameListViewModel.State())
        handler.onConfirm()
        // swap off: confirm is Play fresh, launches immediately on press
        verify { actions.launchSelected(romItem, false) }
    }

    @Test fun north_hold_opens_picker_when_swap_off() {
        every { settings.swapPlayResume } returns false
        every { glvm.state } returns mockk(relaxed = true)
        every { actions.buildSaveStatePicker(rom, any()) } returns
            LauncherScreen.SaveStatePicker(rom, "/x/base", List(11) { false }, 0)
        handler.onNorth()                // arm (north is Resume when swap off)
        handler.resumeHoldRunnable.run()
        assertTrue(nav.currentScreen is LauncherScreen.SaveStatePicker)
        verify { actions.buildSaveStatePicker(rom, false) }
    }
}
