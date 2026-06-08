package dev.cannoli.scorza.input.screen

import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class SaveStatePickerInputHandlerTest {

    private lateinit var nav: NavigationController
    private lateinit var actions: LauncherActions
    private lateinit var handler: SaveStatePickerInputHandler

    private val rom = Rom(id = 1L, path = File("/x/Zelda.sfc"), platformTag = "snes", displayName = "Zelda")

    private fun show(selected: Int, occupied: List<Boolean>, awaitConfirmRelease: Boolean = false) {
        nav.push(LauncherScreen.SaveStatePicker(rom, "/x/base", occupied, selected, awaitConfirmRelease))
    }

    @Before fun setup() {
        nav = NavigationController()
        actions = mockk(relaxed = true)
        handler = SaveStatePickerInputHandler(nav, actions)
    }

    private fun current() = nav.currentScreen as LauncherScreen.SaveStatePicker

    @Test fun right_advances_slot() {
        show(selected = 1, occupied = List(11) { false })
        handler.onRight()
        assertEquals(2, current().selectedSlotIndex)
    }

    @Test fun left_wraps_below_zero_to_ten() {
        show(selected = 0, occupied = List(11) { false })
        handler.onLeft()
        assertEquals(10, current().selectedSlotIndex)
    }

    @Test fun confirm_on_occupied_slot_launches() {
        val occ = List(11) { it == 3 }
        show(selected = 3, occupied = occ)
        every { actions.launchRomFromSlot(rom, 3) } returns null
        handler.onConfirm()
        verify { actions.launchRomFromSlot(rom, 3) }
    }

    @Test fun confirm_launch_pops_picker() {
        show(selected = 3, occupied = List(11) { it == 3 })
        val depth = nav.screenStack.size
        every { actions.launchRomFromSlot(rom, 3) } returns null
        handler.onConfirm()
        assertEquals(depth - 1, nav.screenStack.size)
    }

    @Test fun confirm_launch_error_does_not_pop() {
        show(selected = 3, occupied = List(11) { it == 3 })
        val depth = nav.screenStack.size
        every { actions.launchRomFromSlot(rom, 3) } returns mockk(relaxed = true)
        handler.onConfirm()
        assertEquals(depth, nav.screenStack.size)
    }

    @Test fun confirm_on_empty_slot_is_noop() {
        show(selected = 4, occupied = List(11) { false })
        handler.onConfirm()
        verify(exactly = 0) { actions.launchRomFromSlot(any(), any()) }
    }

    @Test fun confirm_ignored_while_awaiting_release() {
        show(selected = 3, occupied = List(11) { it == 3 }, awaitConfirmRelease = true)
        handler.onConfirm()
        verify(exactly = 0) { actions.launchRomFromSlot(any(), any()) }
    }

    @Test fun confirm_up_clears_await_then_confirm_launches() {
        show(selected = 3, occupied = List(11) { it == 3 }, awaitConfirmRelease = true)
        every { actions.launchRomFromSlot(rom, 3) } returns null
        handler.onConfirmUp()
        assertEquals(false, current().awaitConfirmRelease)
        handler.onConfirm()
        verify { actions.launchRomFromSlot(rom, 3) }
    }

    @Test fun back_pops_the_screen() {
        nav.push(LauncherScreen.SaveStatePicker(rom, "/x/base", List(11) { false }, 0))
        val depth = nav.screenStack.size
        handler.onBack()
        assertEquals(depth - 1, nav.screenStack.size)
    }
}
