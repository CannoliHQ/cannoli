package dev.cannoli.igm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IGMControllerSlotRefreshTest {

    private class CountingBridge : FakeEmulatorBridge() {
        var existsCalls = 0
        override fun stateExists(slot: Int): Boolean {
            existsCalls++
            return false
        }
    }

    // Opening the menu must not block on the filesystem. The state is filled once the
    // dispatcher runs, not before openMenu returns.
    @Test fun `openMenu does not read slot state synchronously`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = CountingBridge()
        val controller = IGMController(bridge, "Game", TestScope(testScheduler), dispatcher)

        controller.openMenu()
        assertEquals("no filesystem work before the menu is shown", 0, bridge.existsCalls)

        advanceUntilIdle()
        assertEquals("12 checks once the refresh runs", 12, bridge.existsCalls)
    }

    // Occupancy only changes when a state is written or deleted, both of which go through
    // this controller, so reopening the menu must not stat all eleven slots again.
    @Test fun `reopening the menu does not re-read occupancy`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = CountingBridge()
        val controller = IGMController(bridge, "Game", TestScope(testScheduler), dispatcher)

        controller.openMenu()
        advanceUntilIdle()
        val afterFirst = bridge.existsCalls

        controller.closeMenu()
        controller.openMenu()
        advanceUntilIdle()

        assertEquals("only the selected slot is re-checked", afterFirst + 1, bridge.existsCalls)
    }

    @Test fun `invalidating the cache makes the next open re-read occupancy`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = CountingBridge()
        val controller = IGMController(bridge, "Game", TestScope(testScheduler), dispatcher)

        controller.openMenu()
        advanceUntilIdle()
        val afterFirst = bridge.existsCalls

        controller.invalidateSlotCache()
        controller.openMenu()
        advanceUntilIdle()

        assertEquals("all twelve again", afterFirst + 12, bridge.existsCalls)
    }
}
