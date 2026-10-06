package dev.cannoli.scorza.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class MappingListRowTest {

    private fun entry(tag: String, group: String?) =
        EmulatorMappingEntry(
            tag = tag,
            platformName = tag,
            coreDisplayName = "core",
            runnerLabel = "runner",
            group = group,
        )

    private fun shape(rows: List<MappingListRow>) = rows.map {
        when (it) {
            is MappingListRow.Group -> "# ${it.label}"
            is MappingListRow.Platform -> it.entry.tag
        }
    }

    @Test
    fun `a header opens each run of a group`() {
        val rows = groupMappingRows(
            listOf(
                entry("NES", "Nintendo"),
                entry("SNES", "Nintendo"),
                entry("MD", "Sega"),
            ),
        )
        assertEquals(listOf("# Nintendo", "NES", "SNES", "# Sega", "MD"), shape(rows))
    }

    @Test
    fun `only the platform rows are selectable`() {
        val rows = groupMappingRows(listOf(entry("NES", "Nintendo"), entry("MD", "Sega")))
        assertEquals(2, rows.count { it.isSelectable })
    }

    @Test
    fun `an ungrouped entry gets no header`() {
        val rows = groupMappingRows(listOf(entry("NES", null), entry("MD", null)))
        assertEquals(listOf("NES", "MD"), shape(rows))
    }

    @Test
    fun `a group that reappears opens a second header`() {
        val rows = groupMappingRows(
            listOf(
                entry("NES", "Nintendo"),
                entry("MD", "Sega"),
                entry("SNES", "Nintendo"),
            ),
        )
        assertEquals(listOf("# Nintendo", "NES", "# Sega", "MD", "# Nintendo", "SNES"), shape(rows))
    }
}
