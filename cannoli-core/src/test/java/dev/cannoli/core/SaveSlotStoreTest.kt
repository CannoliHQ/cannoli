package dev.cannoli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveSlotStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val store: SaveSlotStore
        get() = SaveSlotStore(File(folder.root, "Game.state").absolutePath)

    private fun writeSlot(slot: Int, contents: String) {
        File(store.statePath(slot)).writeText(contents)
        File(store.thumbnailPath(slot)).writeText("$contents.png")
    }

    private fun stateOf(slot: Int): String? =
        File(store.statePath(slot)).takeIf { it.exists() }?.readText()

    private fun thumbnailOf(slot: Int): String? =
        File(store.thumbnailPath(slot)).takeIf { it.exists() }?.readText()

    @Test
    fun autoBecomesTheFirstManualSlot() {
        writeSlot(0, "auto")

        store.rotateAutoIntoHistory()

        assertEquals("auto", stateOf(1))
        assertFalse(store.exists(0))
    }

    @Test
    fun manualSlotsShiftDownOne() {
        writeSlot(0, "auto")
        writeSlot(1, "one")
        writeSlot(2, "two")

        store.rotateAutoIntoHistory()

        assertEquals("auto", stateOf(1))
        assertEquals("one", stateOf(2))
        assertEquals("two", stateOf(3))
    }

    @Test
    fun thumbnailsFollowTheirState() {
        writeSlot(0, "auto")
        writeSlot(1, "one")

        store.rotateAutoIntoHistory()

        assertEquals("auto.png", thumbnailOf(1))
        assertEquals("one.png", thumbnailOf(2))
    }

    @Test
    fun theLastSlotFallsOffTheEnd() {
        val store = store
        writeSlot(0, "auto")
        for (slot in 1 until store.slotCount) writeSlot(slot, "slot$slot")

        store.rotateAutoIntoHistory()

        assertEquals("auto", stateOf(1))
        assertEquals("slot9", stateOf(store.slotCount - 1))
    }

    @Test
    fun anEmptySlotDoesNotSwallowTheOneBelowIt() {
        writeSlot(0, "auto")
        writeSlot(2, "two")

        store.rotateAutoIntoHistory()

        assertEquals("auto", stateOf(1))
        assertEquals("two", stateOf(3))
    }

    @Test
    fun rotatingWithNothingSavedIsHarmless() {
        store.rotateAutoIntoHistory()

        assertFalse(store.exists(0))
        assertFalse(store.exists(1))
    }

    @Test
    fun occupancyReportsEverySlot() {
        val store = store
        writeSlot(0, "auto")
        writeSlot(3, "three")

        val occupancy = store.occupancy()

        assertEquals(store.slotCount, occupancy.size)
        assertTrue(occupancy[0])
        assertTrue(occupancy[3])
        assertFalse(occupancy[1])
    }

    @Test
    fun deleteRemovesTheStateAndItsThumbnail() {
        writeSlot(1, "one")

        store.delete(1)

        assertFalse(store.exists(1))
        assertFalse(File(store.thumbnailPath(1)).exists())
    }
}
