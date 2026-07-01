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

    @Test fun `games fold into one group per group key`() {
        val groups = RommVariantFolder.fold(listOf(
            game(id = 7, groupKey = 7, regions = listOf("USA")),
            game(id = 42, groupKey = 7, regions = listOf("Japan")),
            game(id = 9, groupKey = 9, name = "Other"),
        ))
        assertEquals(2, groups.size)
        assertEquals(2, groups.first { it.representative.groupKey == 7 }.count)
        assertEquals(1, groups.first { it.representative.groupKey == 9 }.count)
    }

    @Test fun `main sibling wins the representative`() {
        val g = RommVariantFolder.fold(listOf(
            game(id = 7, groupKey = 7, regions = listOf("Japan")),
            game(id = 42, groupKey = 7, regions = listOf("USA"), main = true),
        )).single()
        assertEquals(42, g.representative.id)
    }

    @Test fun `without a main sibling USA is preferred over Europe and Japan`() {
        val g = RommVariantFolder.fold(listOf(
            game(id = 7, groupKey = 7, regions = listOf("Japan")),
            game(id = 8, groupKey = 7, regions = listOf("Europe")),
            game(id = 9, groupKey = 7, regions = listOf("USA")),
        )).single()
        assertEquals(9, g.representative.id)
    }

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

    @Test fun `foldCrossPlatform resolves present per member using its own platform tag`() {
        val snes = game(id = 1, groupKey = 1, name = "Zelda").copy(platformId = 12)
        val snesJp = game(id = 2, groupKey = 1, name = "Zelda").copy(platformId = 12)
        val gba = game(id = 9, groupKey = 9, name = "Metroid").copy(platformId = 20)
        val tagFor = { g: RommGame -> if (g.platformId == 12) "snes" else "gba" }
        val presentByTag = mapOf("gba" to setOf(9))
        val rows = dev.cannoli.scorza.ui.viewmodel.RommBrowseFolding.foldCrossPlatform(
            games = listOf(snes, snesJp, gba),
            tagForGame = tagFor,
            localStateForGame = { g, tag ->
                if (g.id in (presentByTag[tag] ?: emptySet())) LocalState.PRESENT else LocalState.REMOTE
            },
        )
        assertEquals(2, rows.size)
        val zelda = rows.first { it.game.name == "Zelda" }
        assertEquals(2, zelda.versionCount)
        assertEquals(false, zelda.anyPresent)
        val metroid = rows.first { it.game.name == "Metroid" }
        assertEquals(LocalState.PRESENT, metroid.localState)
        assertEquals(true, metroid.anyPresent)
    }

    @Test fun `folding a page yields one row per group with counts`() {
        val rows = dev.cannoli.scorza.ui.viewmodel.RommBrowseFolding.toRows(
            groups = RommVariantFolder.fold(listOf(
                game(id = 7, groupKey = 7, name = "A", regions = listOf("USA")),
                game(id = 42, groupKey = 7, name = "A", regions = listOf("Japan")),
                game(id = 9, groupKey = 9, name = "B"),
            )),
            presentIds = setOf(42),
        )
        val a = rows.first { it.game.name == "A" }
        assertEquals(2, a.versionCount)
        assertEquals(true, a.anyPresent)
        val b = rows.first { it.game.name == "B" }
        assertEquals(1, b.versionCount)
        assertEquals(false, b.anyPresent)
    }
}
