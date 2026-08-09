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
        CheevosOverrideMigration(ra, 42).scrubIfNeeded()

        for (k in sessionKeys) assertFalse(k, keysIn(base).contains(k))
        assertTrue(keysIn(base).contains("video_fullscreen"))
        assertTrue(keysIn(base).contains("input_driver"))
    }

    @Test fun `strips the session keys from a dirty override dump under a core dir`() {
        val ra = dir()
        val override = File(File(ra, "mgba_libretro"), "GBA.cfg")
        override.parentFile!!.mkdirs()
        override.writeText(dirtyBlock)
        CheevosOverrideMigration(ra, 42).scrubIfNeeded()

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
        CheevosOverrideMigration(ra, 42).scrubIfNeeded()

        val keys = keysIn(base)
        assertTrue(keys.contains("cheevos_challenge_indicators"))
        assertTrue(keys.contains("cheevos_richpresence_enable"))
        assertTrue(keys.contains("cheevos_visibility_summary"))
        for (k in sessionKeys) assertFalse(k, keys.contains(k))
    }

    @Test fun `runs once per version then no-ops`() {
        val ra = dir()
        val base = File(ra, "retroarch.cfg")
        base.writeText(dirtyBlock)
        CheevosOverrideMigration(ra, 42).scrubIfNeeded()
        assertEquals(emptySet<String>(), keysIn(base))

        // A config re-polluted after the stamp is written is not re-scrubbed at the same version.
        base.writeText(dirtyBlock)
        CheevosOverrideMigration(ra, 42).scrubIfNeeded()
        assertTrue(keysIn(base).contains("cheevos_token"))

        // A version bump re-runs it.
        CheevosOverrideMigration(ra, 43).scrubIfNeeded()
        assertEquals(emptySet<String>(), keysIn(base))
    }

    @Test fun `preserves a trailing newline`() {
        val ra = dir()
        val base = File(ra, "retroarch.cfg")
        base.writeText("cheevos_token = \"oldtoken\"\nvideo_fullscreen = \"true\"\n")
        CheevosOverrideMigration(ra, 42).scrubIfNeeded()
        assertEquals("video_fullscreen = \"true\"\n", base.readText())
    }
}
