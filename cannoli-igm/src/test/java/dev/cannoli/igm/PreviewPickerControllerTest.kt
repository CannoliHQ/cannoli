package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPickerControllerTest {

    private fun controller(
        items: List<String> = listOf("None", "Fancy Bezel", "CRT Frame"),
        selected: String? = "Fancy Bezel",
    ): Pair<PreviewPickerController, MutableList<Int>> {
        val applied = mutableListOf<Int>()
        val c = PreviewPickerController().apply {
            this.items.value = items
            this.selected.value = selected
            onPreview = { applied.add(it) }
        }
        return c to applied
    }

    // The live read is deferred to open time because RetroArch has no settings system during
    // onCreate; asking it for one there crashed the emulator process.
    @Test fun `opening re-reads live state first`() {
        val (c, _) = controller(selected = null)
        c.onRefresh = { c.selected.value = "CRT Frame" }
        assertEquals(2, c.refresh())
    }

    @Test fun `opens on what is already in force`() {
        val (c, _) = controller()
        assertEquals(1, c.refresh())
    }

    @Test fun `opens at the start when nothing is chosen`() {
        val (c, _) = controller(selected = null)
        assertEquals(0, c.refresh())
    }

    @Test fun `opens at the start when the chosen entry has gone`() {
        val (c, _) = controller(selected = "Deleted")
        assertEquals(0, c.refresh())
    }

    @Test fun `every move applies immediately`() {
        val (c, applied) = controller()
        assertEquals(2, c.cycle(1, 1))
        assertEquals(listOf(2), applied)
    }

    // Reopening lands on what was left showing, since a move is the change rather than a proposal.
    @Test fun `a move updates what counts as in force`() {
        val (c, _) = controller()
        c.cycle(1, 1)
        assertEquals("CRT Frame", c.selected.value)
        assertEquals(2, c.refresh())
    }

    // Wraps, so a short list can be walked in one direction rather than reversing at the ends.
    @Test fun `cycling wraps at both ends`() {
        val (c, applied) = controller()
        assertEquals(2, c.cycle(0, -1))
        assertEquals(0, c.cycle(2, 1))
        assertEquals(listOf(2, 0), applied)
    }

    @Test fun `a single entry has nowhere to go and applies nothing`() {
        val (c, applied) = controller(items = listOf("Only"), selected = "Only")
        assertEquals(0, c.cycle(0, 1))
        assertTrue(applied.isEmpty())
    }

    @Test fun `an empty list neither moves nor applies`() {
        val (c, applied) = controller(items = emptyList(), selected = null)
        assertEquals(0, c.cycle(0, 1))
        assertTrue(applied.isEmpty())
    }
}
