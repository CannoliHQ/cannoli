package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * These three cores stay in the catalogue because another platform keeps them, so the only place
 * they can be cut is here. Their primary system is not the one they are excluded from, and that
 * platform already keeps a better answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreExclusionsTest {

    private fun repo(): CoreInfoRepository {
        val assets = ApplicationProvider
            .getApplicationContext<android.content.Context>().assets
        return CoreInfoRepository(assets).also { it.load() }
    }

    private fun idsFor(tag: String) = repo().getCoresForTag(tag).map { it.id }

    @Test fun `Mesen-S is offered for SNES but not for Game Boy`() {
        assertTrue("mesen-s_libretro" in idsFor("SNES"))
        assertFalse("mesen-s_libretro" in idsFor("GB"))
        assertFalse("mesen-s_libretro" in idsFor("GBC"))
    }

    @Test fun `VBA-M is offered for GBA but not for Game Boy`() {
        assertTrue("vbam_libretro" in idsFor("GBA"))
        assertFalse("vbam_libretro" in idsFor("GB"))
        assertFalse("vbam_libretro" in idsFor("GBC"))
    }

    // Excluded from GB only: GBC is not one of the databases SkyEmu claims.
    @Test fun `SkyEmu is offered for GBA but not for Game Boy`() {
        assertTrue("skyemu_libretro" in idsFor("GBA"))
        assertFalse("skyemu_libretro" in idsFor("GB"))
    }

    // blueMSX is the default on ColecoVision and one of four on SG-1000, where Genesis Plus GX is
    // the default. Cutting it there leaves it serving a single platform, so its system files have
    // one destination rather than two.
    @Test fun `blueMSX is offered for ColecoVision but not for SG-1000`() {
        assertTrue("bluemsx_libretro" in idsFor("COLECOVISION"))
        assertFalse("bluemsx_libretro" in idsFor("SG1000"))
    }

    // The cap is the reason all of this exists, so it is asserted rather than assumed.
    @Test fun `no platform offers more than six cores`() {
        val r = repo()
        val tags = listOf(
            "NES", "FDS", "GB", "GBC", "GBA", "SNES", "N64", "NDS", "VIRTUALBOY", "POKEMINI",
            "SG1000", "SMS", "MD", "GG", "SEGACD", "32X", "SATURN", "DC",
            "PS", "PSP", "ATARI2600", "ATARI5200", "ATARI7800", "LYNX", "JAGUAR",
            "PCE", "SUPERGRAFX", "PCFX", "NEOGEO", "NGP", "NGPC", "WS", "WSC",
            "MAME", "FBN", "DOS", "AMIGA", "SCUMMVM", "INTELLIVISION", "COLECOVISION", "VECTREX",
        )
        val over = tags.map { it to r.getCoresForTag(it).size }.filter { it.second > 6 }
        assertTrue(
            "these platforms exceed the six-core cap:\n" +
                over.joinToString("\n") { "  ${it.first}: ${it.second}" },
            over.isEmpty(),
        )
    }
}
