package dev.cannoli.scorza.romm.art

import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommGame
import org.junit.Assert.assertEquals
import org.junit.Test

class RommArtMatcherTest {

    private fun game(id: Int, fsName: String, cover: String?) =
        RommGame(id, 1, "Game $id", fsName, 0L, null, null, emptyList(), emptyList(), cover, emptyList())

    private val zelda = game(2, "Zelda.sfc", "covers/2.png")
    private val byFsName = mapOf("zelda.sfc" to zelda)
    private val byId = mapOf(2 to zelda)

    @Test fun `already has art short-circuits`() {
        assertEquals(ArtOutcome.AlreadyHasArt,
            RommArtMatcher.decide(true, "Zelda.sfc", null, byFsName, byId))
    }

    @Test fun `filename match when not linked`() {
        assertEquals(ArtOutcome.Found(zelda),
            RommArtMatcher.decide(false, "Zelda.sfc", null, byFsName, byId))
    }

    @Test fun `filename match is case-insensitive`() {
        assertEquals(ArtOutcome.Found(zelda),
            RommArtMatcher.decide(false, "zelda.SFC", null, byFsName, byId))
    }

    @Test fun `linked id wins over filename`() {
        val other = game(2, "renamed.sfc", "covers/2.png")
        assertEquals(ArtOutcome.Found(other),
            RommArtMatcher.decide(false, "renamed.sfc", 2, emptyMap(), mapOf(2 to other)))
    }

    @Test fun `no match when filename absent and no link`() {
        assertEquals(ArtOutcome.NoMatch,
            RommArtMatcher.decide(false, "Missing.sfc", null, byFsName, byId))
    }

    @Test fun `no match when matched game has no cover`() {
        val noCover = game(3, "Metroid.sfc", null)
        assertEquals(ArtOutcome.NoMatch,
            RommArtMatcher.decide(false, "Metroid.sfc", null, mapOf("metroid.sfc" to noCover), emptyMap()))
    }

    @Test fun `a hack and the base game file take their parent entry's cover`() {
        val tecmo = RommGame(
            1343, 1, "Tecmo Super Bowl", "Tecmo Super Bowl", 0L, null, null, emptyList(), emptyList(), "covers/1343.png",
            listOf(
                RommFile("Tecmo Super Bowl (USA).nes", 1, null, null, null, id = 1832, category = "game", isTopLevel = true),
                RommFile("Tecmo Super Bowl 2025.nes", 1, null, null, null, id = 1833, subDir = "hacks", category = "hack"),
            ),
        )
        val index = RommArtMatcher.byFileName(listOf(tecmo))
        assertEquals(ArtOutcome.Found(tecmo), RommArtMatcher.decide(false, "Tecmo Super Bowl 2025.nes", null, index, emptyMap()))
        assertEquals(ArtOutcome.Found(tecmo), RommArtMatcher.decide(false, "Tecmo Super Bowl (USA).nes", null, index, emptyMap()))
    }

    @Test fun `an fs name keeps its own entry over another entry's hack`() {
        val own = game(9, "Hack.nes", "covers/9.png")
        val parent = RommGame(
            1, 1, "P", "P", 0L, null, null, emptyList(), emptyList(), "covers/1.png",
            listOf(RommFile("Hack.nes", 1, null, null, null, category = "hack")),
        )
        val index = RommArtMatcher.byFileName(listOf(parent, own))
        assertEquals(ArtOutcome.Found(own), RommArtMatcher.decide(false, "Hack.nes", null, index, emptyMap()))
    }
}
