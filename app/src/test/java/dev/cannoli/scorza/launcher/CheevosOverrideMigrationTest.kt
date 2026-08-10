package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The migration is the on-disk half of the fix: the launch config injects the session keys fresh,
 * so every other config has to lose them or a stale copy can layer back over the fresh value.
 */
class CheevosOverrideMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sessionKeys = listOf(
        "cheevos_enable",
        "cheevos_hardcore_mode_enable",
        "cheevos_username",
        "cheevos_token",
        "cheevos_password",
    )

    private fun dir() = tmp.newFolder("RetroArch")

    private fun keysIn(file: File): Set<String> =
        file.readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i < 0) null else line.take(i).trim()
        }.toSet()

    private val dirtyBlock = """
        cheevos_enable = "true"
        cheevos_username = "olduser"
        cheevos_token = "oldtoken"
        cheevos_password = "hunter2"
        cheevos_hardcore_mode_enable = "true"
    """.trimIndent()

    @Test fun `strips the session keys from a dirty base config`() {
        val ra = dir()
        val base = File(ra, "retroarch.cfg")
        base.writeText(
            """
            video_fullscreen = "true"
            $dirtyBlock
            input_driver = "android"
            """.trimIndent()
        )
        CheevosOverrideMigration { ra }.scrubIfNeeded()

        for (k in sessionKeys) assertFalse(k, keysIn(base).contains(k))
        assertTrue(keysIn(base).contains("video_fullscreen"))
        assertTrue(keysIn(base).contains("input_driver"))
    }

    @Test fun `strips the session keys from a dirty override dump under a core dir`() {
        val ra = dir()
        val override = File(File(ra, "mgba_libretro"), "GBA.cfg")
        override.parentFile!!.mkdirs()
        override.writeText(dirtyBlock)
        CheevosOverrideMigration { ra }.scrubIfNeeded()

        assertEquals(emptySet<String>(), keysIn(override))
    }

    @Test fun `leaves display and preference cheevos keys alone`() {
        val ra = dir()
        val base = File(ra, "retroarch.cfg")
        base.writeText(
            """
            cheevos_challenge_indicators = "true"
            cheevos_richpresence_enable = "true"
            cheevos_visibility_summary = "1"
            $dirtyBlock
            """.trimIndent()
        )
        CheevosOverrideMigration { ra }.scrubIfNeeded()

        val keys = keysIn(base)
        assertTrue(keys.contains("cheevos_challenge_indicators"))
        assertTrue(keys.contains("cheevos_richpresence_enable"))
        assertTrue(keys.contains("cheevos_visibility_summary"))
        for (k in sessionKeys) assertFalse(k, keys.contains(k))
    }

    @Test fun `re-scrubs a config re-polluted after a previous run`() {
        val ra = dir()
        val base = File(ra, "retroarch.cfg")
        base.writeText(dirtyBlock)
        val migration = CheevosOverrideMigration { ra }
        migration.scrubIfNeeded()
        assertEquals(emptySet<String>(), keysIn(base))

        // Nothing gates a re-run on having already scrubbed this tree, so a config re-polluted by a
        // restored backup, an older side-loaded build, or a hand-edit is scrubbed again rather than
        // being permanently skipped.
        base.writeText(dirtyBlock)
        migration.scrubIfNeeded()
        assertEquals(emptySet<String>(), keysIn(base))
    }

    @Test fun `resolves the config directory fresh on every call, not once at construction`() {
        val staleRoot = tmp.newFolder("RetroArch-stale")
        val freshRoot = tmp.newFolder("RetroArch-fresh")
        File(staleRoot, "retroarch.cfg").writeText(dirtyBlock)
        File(freshRoot, "retroarch.cfg").writeText(dirtyBlock)

        var current = staleRoot
        val migration = CheevosOverrideMigration { current }
        current = freshRoot
        migration.scrubIfNeeded()

        assertEquals(emptySet<String>(), keysIn(File(freshRoot, "retroarch.cfg")))
        assertTrue(keysIn(File(staleRoot, "retroarch.cfg")).contains("cheevos_token"))
    }

    @Test fun `deletes the stale scrub version stamp left by an older build`() {
        val ra = dir()
        val stamp = File(ra, ".cheevos_scrub_version")
        stamp.writeText("123")
        File(ra, "retroarch.cfg").writeText(dirtyBlock)

        CheevosOverrideMigration { ra }.scrubIfNeeded()

        assertFalse("the stale stamp must be deleted", stamp.exists())
    }

    @Test fun `never writes a scrub version stamp`() {
        val ra = dir()
        File(ra, "retroarch.cfg").writeText(dirtyBlock)

        CheevosOverrideMigration { ra }.scrubIfNeeded()

        assertFalse(File(ra, ".cheevos_scrub_version").exists())
    }

    @Test fun `preserves a trailing newline`() {
        val ra = dir()
        val base = File(ra, "retroarch.cfg")
        base.writeText("cheevos_token = \"oldtoken\"\nvideo_fullscreen = \"true\"\n")
        CheevosOverrideMigration { ra }.scrubIfNeeded()
        assertEquals("video_fullscreen = \"true\"\n", base.readText())
    }
}
