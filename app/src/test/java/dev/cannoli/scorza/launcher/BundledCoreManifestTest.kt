package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledCoreManifestTest {

    private val manifest = """
        # written by scripts/record_bundled_core.py
        #
        # <abi> <core> <etag> <built>
        arm64-v8a nestopia_libretro "n64tag" 2026-08-20
        arm64-v8a snes9x_libretro "s64tag" 2026-08-25
        armeabi-v7a nestopia_libretro "n32tag" 2026-08-20
        armeabi-v7a snes9x_libretro "s32tag" 2026-08-25
    """.trimIndent()

    private fun parse(abi: String) =
        BundledCoreManifest.parse(manifest.lineSequence(), abi)

    @Test fun `only the running ABI answers`() {
        assertEquals("\"s64tag\"", parse("arm64-v8a")["snes9x_libretro"]?.etag)
        assertEquals("\"s32tag\"", parse("armeabi-v7a")["snes9x_libretro"]?.etag)
    }

    @Test fun `an unknown ABI matches nothing rather than guessing`() {
        assertTrue(parse("x86_64").isEmpty())
    }

    @Test fun `comments and blank lines are not entries`() {
        assertEquals(2, parse("arm64-v8a").size)
    }

    @Test fun `the build date comes back with the etag`() {
        assertEquals("2026-08-20", parse("arm64-v8a")["nestopia_libretro"]?.built)
    }

    @Test fun `a core that was never bundled has no entry`() {
        assertNull(parse("arm64-v8a")["fbneo_libretro"])
    }

    @Test fun `a malformed row is skipped rather than losing the file`() {
        val rows = BundledCoreManifest.parse(
            sequenceOf(
                "arm64-v8a snes9x_libretro \"ok\" 2026-08-25",
                "arm64-v8a truncated_libretro \"etag-but-no-date\"",
                "garbage",
            ),
            "arm64-v8a",
        )
        assertEquals(1, rows.size)
        assertEquals("2026-08-25", rows["snes9x_libretro"]?.built)
    }

    @Test fun `an unknown build date reads as absent`() {
        val rows = BundledCoreManifest.parse(sequenceOf("arm64-v8a x_libretro \"t\" ?"), "arm64-v8a")
        assertEquals("?", rows["x_libretro"]?.built)
    }
}
