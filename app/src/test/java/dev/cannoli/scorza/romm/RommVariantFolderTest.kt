package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Test

class RommVariantFolderTest {
    private fun game(
        id: Int, groupKey: Int, name: String = "Game",
        regions: List<String> = emptyList(), revision: String? = null,
        fsName: String = "game-$id.zip", main: Boolean = false,
    ) = RommGame(
        id = id, platformId = 1, name = name, fsName = fsName, sizeBytes = 0,
        summary = null, revision = revision, regions = regions, languages = emptyList(),
        coverPath = null, files = emptyList(), groupKey = groupKey, isMainSibling = main,
    )

    @Test fun `label uses region and revision`() {
        assertEquals("USA", RommVariantFolder.variantLabel(
            game(id = 1, groupKey = 1, regions = listOf("USA")), formatRev = { "Rev $it" }))
        assertEquals("USA (Rev 1)", RommVariantFolder.variantLabel(
            game(id = 1, groupKey = 1, regions = listOf("USA"), revision = "1"), formatRev = { "Rev $it" }))
    }

    @Test fun `label falls back to the filename when there is no region`() {
        assertEquals("Chrono Trigger Spanish v1", RommVariantFolder.variantLabel(
            game(id = 1, groupKey = 1, fsName = "Chrono Trigger Spanish v1.sfc"), formatRev = { "Rev $it" }))
    }
}
