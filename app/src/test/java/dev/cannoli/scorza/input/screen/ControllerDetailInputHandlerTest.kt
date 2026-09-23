package dev.cannoli.scorza.input.screen

import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.MappingSource
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.ControllersUiState
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerDetailInputHandlerTest {

    private fun mapping(edited: Boolean) = DeviceMapping(
        id = "pad",
        displayName = "Pad",
        match = DeviceMatchRule(),
        bindings = mapOf(CanonicalButton.BTN_SOUTH to emptyList()),
        source = MappingSource.USER_WIZARD,
        userEdited = edited,
    )

    private fun setUp(edited: Boolean, selectedIndex: Int = 0): Triple<ControllerDetailInputHandler, NavigationController, ControllersViewModel> {
        val m = mapping(edited)
        val vm = mockk<ControllersViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(ControllersUiState(savedMappings = listOf(m)))
        val nav = NavigationController()
        nav.push(LauncherScreen.ControllerDetail(mappingId = m.id, selectedIndex = selectedIndex))
        return Triple(ControllerDetailInputHandler(nav, vm), nav, vm)
    }

    @Test fun `west asks before resetting an edited mapping`() {
        val (h, nav, vm) = setUp(edited = true)

        h.onWest()

        assertEquals(DialogState.ControllerResetConfirm("pad"), nav.dialogState.value)
        verify(exactly = 0) { vm.resetMapping(any()) }
    }

    @Test fun `west does nothing on a mapping with no edits`() {
        val (h, nav, _) = setUp(edited = false)

        h.onWest()

        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `a confirmed reset resets and leaves the screen`() {
        val (h, nav, vm) = setUp(edited = true)

        h.resetConfirmed("pad")

        verify(exactly = 1) { vm.resetMapping(match { it.id == "pad" }) }
        assertTrue(nav.currentScreen !is LauncherScreen.ControllerDetail)
    }

    @Test fun `the list no longer has a reset row`() {
        val (h, _, vm) = setUp(edited = true, selectedIndex = 5)

        h.onConfirm()

        verify(exactly = 0) { vm.resetMapping(any()) }
    }
}
