package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A row's label is `curatedRowLabels[row.key] ?: row.key`, so a key the map does not carry ships the
 * raw identifier as the label instead of failing. The catalogue and the labels are written in
 * separate files and nothing else makes them agree.
 */
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
