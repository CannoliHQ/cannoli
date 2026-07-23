package dev.cannoli.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionedListTest {

    @Test
    fun flattenItems_concatenatesInOrder() {
        val sections = listOf(
            ListSection(header = null, items = listOf("a", "b")),
            ListSection(header = "DONE", items = listOf("c"))
        )
        assertEquals(listOf("a", "b", "c"), sections.flattenItems())
    }

    @Test
    fun headerIndices_mapsFirstItemOfLabeledSections() {
        val sections = listOf(
            ListSection(header = null, items = listOf("a", "b")),
            ListSection(header = "DONE", items = listOf("c", "d"))
        )
        assertEquals(mapOf(2 to "DONE"), sections.headerIndices())
    }

    @Test
    fun headerIndices_skipsEmptySectionsWithoutShiftingIndex() {
        val sections = listOf(
            ListSection(header = "EMPTY", items = emptyList()),
            ListSection(header = null, items = listOf("a")),
            ListSection(header = "DONE", items = listOf("b"))
        )
        assertEquals(listOf("a", "b"), sections.flattenItems())
        assertEquals(mapOf(1 to "DONE"), sections.headerIndices())
    }

    @Test
    fun headerIndices_allItemsCompletedPutsHeaderAtZero() {
        val sections = listOf(
            ListSection(header = "DONE", items = listOf("a", "b"))
        )
        assertEquals(mapOf(0 to "DONE"), sections.headerIndices())
    }

    @Test
    fun headerIndices_headerlessSectionHasNoEntries() {
        val sections = listOf(
            ListSection(header = null, items = listOf("a", "b"))
        )
        assertEquals(emptyMap<Int, String>(), sections.headerIndices())
    }
}
