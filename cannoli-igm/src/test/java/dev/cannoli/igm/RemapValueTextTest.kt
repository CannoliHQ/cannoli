package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Test

class RemapValueTextTest {

    private fun value(
        listening: Boolean = false,
        target: Int,
        buttonId: Int = RemapButton.SOUTH.id,
        names: Map<Int, String> = emptyMap(),
        unboundText: String = "Unbound",
        listeningText: String = "...",
        fallbackLabel: String = "Fallback",
    ) = remapValueText(listening, target, buttonId, names, unboundText, listeningText, fallbackLabel)

    @Test fun `an unmapped row with a core name is blank`() {
        val text = value(
            target = RemapButton.SOUTH.id,
            buttonId = RemapButton.SOUTH.id,
            names = mapOf(RemapButton.SOUTH.id to "B"),
        )
        assertEquals("", text)
    }

    @Test fun `a remapped row shows the core's name`() {
        val text = value(
            target = RemapButton.EAST.id,
            buttonId = RemapButton.SOUTH.id,
            names = mapOf(RemapButton.EAST.id to "C"),
        )
        assertEquals("C", text)
    }

    @Test fun `unbound beats a name`() {
        val text = value(
            target = ButtonRemap.UNBOUND,
            names = mapOf(ButtonRemap.UNBOUND to "C"),
            unboundText = "Unbound",
        )
        assertEquals("Unbound", text)
    }

    @Test fun `listening beats everything`() {
        val text = value(
            listening = true,
            target = ButtonRemap.UNBOUND,
            names = mapOf(RemapButton.SOUTH.id to "B"),
            listeningText = "...",
        )
        assertEquals("...", text)
    }

    @Test fun `a remapped row with no core name falls back to the target's label`() {
        val text = value(
            target = RemapButton.EAST.id,
            buttonId = RemapButton.SOUTH.id,
            names = emptyMap(),
            fallbackLabel = "A",
        )
        assertEquals("A", text)
    }
}
