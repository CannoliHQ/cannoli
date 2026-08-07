package dev.cannoli.igm

import dev.cannoli.core.SaveSlotStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class IGMControllerSlotRefreshTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val slots: SaveSlotStore
        get() = SaveSlotStore(File(folder.root, "Game.state").absolutePath)

    private fun controller(scheduler: TestCoroutineScheduler) = IGMController(
        FakeRetroArchBridge(),
        "Game",
        slots,
        TestScope(scheduler),
        StandardTestDispatcher(scheduler),
    )

    private fun writeState(slot: Int) {
        File(slots.statePath(slot)).writeText("state")
    }

    @Test fun `openMenu does not read slot state synchronously`() = runTest {
        val controller = controller(testScheduler)

        controller.openMenu()
        assertFalse("no filesystem work before the menu is shown", controller.slotThumbnailLoaded.value)

        advanceUntilIdle()
        assertTrue(controller.slotThumbnailLoaded.value)
        assertEquals(SaveSlotStore.SLOT_COUNT, controller.slotOccupied.value.size)
    }

    @Test fun `reopening the menu on an unchanged slot keeps the polaroid`() = runTest {
        val controller = controller(testScheduler)

        controller.openMenu()
        advanceUntilIdle()

        controller.closeMenu()
        controller.openMenu()

        assertTrue("blanking it here is the flicker", controller.slotThumbnailLoaded.value)
    }

    @Test fun `selecting another slot drops the previous thumbnail at once`() = runTest {
        val controller = controller(testScheduler)

        controller.openMenu()
        advanceUntilIdle()

        controller.selectedSlotIndex.intValue = 1
        controller.refreshSlotInfo()

        assertFalse(
            "another slot's image is wrong, not merely old",
            controller.slotThumbnailLoaded.value,
        )
    }

    @Test fun `a write is picked up once the cache is invalidated`() = runTest {
        val controller = controller(testScheduler)

        controller.openMenu()
        advanceUntilIdle()
        assertFalse(controller.slotOccupied.value[3])

        writeState(3)
        controller.invalidateSlotCache()
        controller.openMenu()
        advanceUntilIdle()

        assertTrue(controller.slotOccupied.value[3])
    }

    @Test fun `a write lands even though saving closed the menu`() = runTest {
        val controller = controller(testScheduler)

        controller.openMenu()
        advanceUntilIdle()
        assertFalse(controller.slotOccupied.value[2])

        controller.closeMenu()
        writeState(2)
        controller.onStateWritten()
        advanceUntilIdle()

        assertTrue(
            "waiting for the next open shows the previous screenshot first",
            controller.slotOccupied.value[2],
        )
    }

    @Test fun `the auto slot is archived before its replacement is queued`() = runTest {
        val bridge = FakeRetroArchBridge()
        val store = slots
        val controller = IGMController(
            bridge, "Game", store, TestScope(testScheduler), StandardTestDispatcher(testScheduler),
        )
        writeState(SaveSlotStore.AUTO_SLOT)

        controller.openMenu()
        controller.saveState()

        assertEquals("state", File(store.statePath(1)).readText())
        assertEquals(listOf(SaveSlotStore.AUTO_SLOT), bridge.savedSlots)
    }
}
