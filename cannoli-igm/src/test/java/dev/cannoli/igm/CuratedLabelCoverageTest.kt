package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Test

class CuratedLabelCoverageTest {

    private val rows = CuratedCatalog.categories.flatMap { it.rows }

    private val strings = RaOptionStrings()

    @Test fun `every row has a label and every label has a row`() {
        assertEquals(
            rows.map { it.key }.toSortedSet(),
            strings.curatedRowLabels.keys.toSortedSet(),
        )
    }

    @Test fun `every preset has a label and every label has a preset`() {
        assertEquals(
            rows.flatMap { row -> row.presets.map { it.labelKey } }.toSortedSet(),
            strings.curatedPresetLabels.keys.toSortedSet(),
        )
    }

    @Test fun `every category shown in either menu has a title`() {
        val shown = setOf(
            CuratedCatalog.CATEGORY_VIDEO,
            CuratedCatalog.CATEGORY_ADVANCED,
            CuratedCatalog.CATEGORY_EMULATOR,
            CuratedCatalog.CATEGORY_INFO,
            CuratedCatalog.CATEGORY_OVERLAY,
            CuratedCatalog.CATEGORY_INPUT,
            CuratedCatalog.CATEGORY_SHADER,
        )
        assertEquals(shown, strings.curatedCategoryTitles.keys)
    }
}
