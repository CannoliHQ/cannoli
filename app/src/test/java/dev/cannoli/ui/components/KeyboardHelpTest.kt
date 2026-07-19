package dev.cannoli.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardHelpTest {

    private fun typeGroup(layout: KeyboardLayout) =
        keyboardHelpGroups(layout).first { grp ->
            grp.entries.any { it.glyphs == listOf(HelpGlyph.CONFIRM) }
        }

    @Test fun `groups are type cursor action in order`() {
        val groups = keyboardHelpGroups(KeyboardLayout.Default)
        assertEquals(3, groups.size)
        assertEquals(listOf(HelpGlyph.DPAD), groups[0].entries.first().glyphs)
        assertEquals(
            listOf(listOf(HelpGlyph.L1, HelpGlyph.R1), listOf(HelpGlyph.L2, HelpGlyph.R2)),
            groups[1].entries.map { it.glyphs }
        )
        assertEquals(
            listOf(listOf(HelpGlyph.START), listOf(HelpGlyph.WEST)),
            groups[2].entries.map { it.glyphs }
        )
    }

    @Test fun `default layout type group leads with dpad and lists all typing keys`() {
        val glyphs = typeGroup(KeyboardLayout.Default).entries.map { it.glyphs }
        assertEquals(
            listOf(
                listOf(HelpGlyph.DPAD),
                listOf(HelpGlyph.CONFIRM),
                listOf(HelpGlyph.BACK),
                listOf(HelpGlyph.NORTH),
                listOf(HelpGlyph.SELECT),
                listOf(HelpGlyph.SELECT),
            ),
            glyphs
        )
    }

    @Test fun `number layout type group drops space caps and symbols`() {
        val glyphs = typeGroup(KeyboardLayout.Number).entries.map { it.glyphs }
        assertEquals(
            listOf(listOf(HelpGlyph.DPAD), listOf(HelpGlyph.CONFIRM), listOf(HelpGlyph.BACK)),
            glyphs
        )
    }
}
