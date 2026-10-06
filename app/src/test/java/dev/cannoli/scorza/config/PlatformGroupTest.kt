package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformGroupTest {

    private val assets = ApplicationProvider
        .getApplicationContext<android.content.Context>().assets

    private fun config(): PlatformConfig =
        PlatformConfig({ File("/tmp/cannoli-group-test") }, assets)

    private fun platformsJson(): JSONObject =
        JSONObject(assets.open("platforms.json").use { it.bufferedReader().readText() })

    @Test
    fun `every platform in the asset declares a group`() {
        val json = platformsJson()
        val missing = json.keys().asSequence()
            .filter { json.getJSONObject(it).optString("group", "").isEmpty() }
            .toList()
        assertTrue(
            "these platforms have no group, so Kitchen would file them under Other:\n" +
                missing.joinToString("\n") { "  $it" },
            missing.isEmpty(),
        )
    }

    @Test
    fun `the group is read back per tag`() {
        val config = config()
        assertEquals("Nintendo", config.getGroup("NES"))
        assertEquals("Sega", config.getGroup("DC"))
        assertEquals("Sony", config.getGroup("PSP"))
    }

    @Test
    fun `lookup is case insensitive`() {
        assertEquals("Nintendo", config().getGroup("nes"))
    }

    @Test
    fun `an unknown tag has no group rather than a wrong one`() {
        assertNull(config().getGroup("NOT_A_PLATFORM"))
    }

    @Test
    fun `groups rank alphabetically, not in the asset's order`() {
        val config = config()
        fun assertBefore(earlier: String, later: String) {
            assertTrue(
                "$earlier should rank before $later",
                config.groupRank(earlier) < config.groupRank(later),
            )
        }
        assertBefore("Arcade", "Nintendo")
        assertBefore("Atari", "Sega")
        assertBefore("NEC", "Sony")
    }

    @Test
    fun `the catch-all group ranks after every maker`() {
        val config = config()
        val json = platformsJson()
        val makers = json.keys().asSequence()
            .map { json.getJSONObject(it).optString("group", "") }
            .filter { it.isNotEmpty() && it != "Other" }
            .distinct()
            .toList()
        val misplaced = makers.filter { config.groupRank(it) > config.groupRank("Other") }
        assertTrue("these groups rank below Other: $misplaced", misplaced.isEmpty())
    }

    @Test
    fun `an unknown group ranks last rather than first`() {
        assertEquals(Int.MAX_VALUE, config().groupRank("NOT_A_GROUP"))
    }

    @Test
    fun `getGroups answers only for the tags it was given`() {
        val groups = config().getGroups(listOf("NES", "PSP", "NOT_A_PLATFORM"))
        assertEquals(mapOf("NES" to "Nintendo", "PSP" to "Sony"), groups)
    }
}
