package dev.cannoli.scorza.ui.screens

import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RommGameDetailLayoutTest {

    private fun game(
        size: Long = 4_194_304L,
        regions: List<String> = listOf("USA"),
        languages: List<String> = listOf("English"),
        revision: String? = "Rev 1",
    ) = RommGame(
        id = 1, platformId = 2, name = "Chrono Trigger", fsName = "ct.sfc", sizeBytes = size,
        summary = "x", revision = revision, regions = regions, languages = languages,
        coverPath = null, files = listOf(RommFile("ct.sfc", size, null, null, null)),
    )

    @Test fun `players list is ordered single player then multiplayer`() {
        val g = game().copy(gameModes = listOf("Multiplayer", "Co-operative", "Single player"))
        val players = RommGameDetailLayout.metadataRows(g).first { it.labelRes == R.string.romm_detail_players }.value
        assertEquals("Single player, Multiplayer, Co-operative", players)
    }

    @Test fun `scaleFor is 1 at reference and clamps at both ends`() {
        assertEquals(1.0f, RommGameDetailLayout.scaleFor(640f, 360f), 0.001f)
        assertEquals(0.85f, RommGameDetailLayout.scaleFor(300f, 300f), 0.001f)   // clamp low
        assertEquals(1.6f, RommGameDetailLayout.scaleFor(1280f, 960f), 0.001f)   // clamp high
        assertTrue(RommGameDetailLayout.scaleFor(420f, 420f) > RommGameDetailLayout.scaleFor(360f, 360f))
    }

    @Test fun `metadataRows keeps order, omits empties, always includes size`() {
        val rows = RommGameDetailLayout.metadataRows(game())
        assertEquals(
            listOf(R.string.romm_detail_region, R.string.romm_detail_languages, R.string.romm_detail_size, R.string.romm_detail_revision),
            rows.map { it.labelRes },
        )
        val sparse = RommGameDetailLayout.metadataRows(game(regions = emptyList(), languages = emptyList(), revision = null))
        assertEquals(listOf(R.string.romm_detail_size), sparse.map { it.labelRes })
    }

    @Test fun `metadataRows includes the rich fields in order`() {
        val g = game().copy(
            companies = listOf("Square"),
            genres = listOf("RPG"),
            gameModes = listOf("Single player"),
            firstReleaseDate = 765072000000L,
        )
        assertEquals(
            listOf(
                R.string.romm_detail_publisher, R.string.romm_detail_released,
                R.string.romm_detail_genre, R.string.romm_detail_players,
                R.string.romm_detail_region, R.string.romm_detail_languages,
                R.string.romm_detail_size, R.string.romm_detail_revision,
            ),
            RommGameDetailLayout.metadataRows(g).map { it.labelRes },
        )
    }

    @Test fun `releaseYear extracts the year or null`() {
        assertEquals("1994", RommGameDetailLayout.releaseYear(765072000000L))
        assertEquals(null, RommGameDetailLayout.releaseYear(null))
    }

    @Test fun `showDownloadAction only when remote and enabled`() {
        assertTrue(RommGameDetailLayout.showDownloadAction(LocalState.REMOTE, downloadEnabled = true))
        assertFalse(RommGameDetailLayout.showDownloadAction(LocalState.REMOTE, downloadEnabled = false))
        assertFalse(RommGameDetailLayout.showDownloadAction(LocalState.PRESENT, downloadEnabled = true))
    }

    @Test fun `formatBytes scales units`() {
        assertEquals("4.0 MB", RommGameDetailLayout.formatBytes(4_194_304L))
        assertEquals("512 B", RommGameDetailLayout.formatBytes(512L))
    }
}
