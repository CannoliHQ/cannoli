package dev.cannoli.scorza.boot

import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.settings.ContentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartOnTargetTest {

    private fun platform(tag: String, name: String = tag, games: Int = 1, tags: List<String> = emptyList()) =
        Platform(tag = tag, displayName = name, coreName = null, gameCount = games, tags = tags)

    private val gba = platform("GBA", "Game Boy Advance", games = 12)
    private val snes = platform("SNES", "Super Nintendo", games = 7)

    @Test fun `no choice opens the system list`() {
        assertNull(startOnTarget("", ContentMode.PLATFORMS, listOf(gba, snes)))
    }

    @Test fun `a chosen platform is the target`() {
        assertEquals(gba, startOnTarget("GBA", ContentMode.PLATFORMS, listOf(gba, snes)))
    }

    // The other two modes build a different system list, so a platform target does not apply.
    @Test fun `the other content modes ignore the choice`() {
        assertNull(startOnTarget("GBA", ContentMode.COLLECTIONS, listOf(gba, snes)))
        assertNull(startOnTarget("GBA", ContentMode.FIVE_GAME_HANDHELD, listOf(gba, snes)))
    }

    // A card that has not mounted at boot, which is the case this is built around.
    @Test fun `a platform that no longer resolves opens the system list`() {
        assertNull(startOnTarget("GBA", ContentMode.PLATFORMS, listOf(snes)))
    }

    @Test fun `a platform that resolves but has no games opens the system list`() {
        assertNull(startOnTarget("GBA", ContentMode.PLATFORMS, listOf(platform("GBA", games = 0))))
    }

    // Matching the group rather than the tag is what opens all of its games, not half.
    @Test fun `a merged platform is matched by either of its tags`() {
        val merged = platform("GB", "Game Boy", games = 20, tags = listOf("GB", "GBC"))
        assertEquals(merged, startOnTarget("GBC", ContentMode.PLATFORMS, listOf(merged)))
        assertEquals(listOf("GB", "GBC"), startOnTarget("GBC", ContentMode.PLATFORMS, listOf(merged))?.allTags)
    }
}
