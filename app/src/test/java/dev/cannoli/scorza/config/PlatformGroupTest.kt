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

/**
 * platforms.json is the source of truth for manufacturer grouping, served to Kitchen over
 * /api/tags so it does not keep a second list. A platform added without a group would land
 * silently in "Other" on the dashboard, which is exactly the drift moving the data here was meant
 * to end, so it is a test rather than a convention.
 */
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

    // Tags reach the api in whatever spelling a user's folder had, and are matched case-insensitively
    // everywhere else, so grouping must not be the one place that cares.
    @Test
    fun `lookup is case insensitive`() {
        assertEquals("Nintendo", config().getGroup("nes"))
    }

    @Test
    fun `an unknown tag has no group rather than a wrong one`() {
        assertNull(config().getGroup("NOT_A_PLATFORM"))
    }

    // The mapping list reads by release year, so the asset's order is load-bearing rather than
    // cosmetic. Each pair below is one an alphabetical sort would invert, which is the regression
    // this guards: sorting by display name put Game Boy above NES and Game Boy Color above SNES.
    @Test
    fun `tags within a group are ordered by release year, not by name`() {
        val config = config()
        fun assertBefore(earlier: String, later: String) {
            assertTrue(
                "$earlier should rank before $later",
                config.tagRank(earlier) < config.tagRank(later),
            )
        }
        assertBefore("NES", "GB")
        assertBefore("SNES", "GBC")
        assertBefore("N64", "GBA")
        assertBefore("SG1000", "SMS")
        assertBefore("PS2", "PSP")
        assertBefore("ATARI2600", "LYNX")
        assertBefore("NEOGEO", "NGP")
    }

    @Test
    fun `an unknown tag ranks last rather than first`() {
        assertEquals(Int.MAX_VALUE, config().tagRank("NOT_A_PLATFORM"))
    }

    // What the tags endpoint hands Kitchen: only the tags asked about, and nothing invented.
    @Test
    fun `getGroups answers only for the tags it was given`() {
        val groups = config().getGroups(listOf("NES", "PSP", "NOT_A_PLATFORM"))
        assertEquals(mapOf("NES" to "Nintendo", "PSP" to "Sony"), groups)
    }
}
