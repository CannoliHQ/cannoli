package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.EmulatorChoice
import dev.cannoli.scorza.config.EmulatorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This decides what a bulk removal deletes. Every case here is written from the same direction:
 * keeping a core that could have gone is a few megabytes, deleting one still in use is a download
 * and a failed launch in between, so anything ambiguous has to count as used.
 */
class CoreUsageTest {

    private fun embedded(core: String) =
        EmulatorChoice(source = EmulatorSource.Embedded, coreId = core)

    @Test fun `a platform's resolved core counts as used`() {
        val used = CoreUsage.usedCoreIds(
            platformTags = listOf("SNES"),
            coreMappingFor = { "snes9x_libretro" },
            overridesFor = { emptyList() },
        )
        assertEquals(setOf("snes9x_libretro"), used)
    }

    // getCoreMapping folds in the built-in default, so a platform nobody has configured still
    // names a core. Reading only explicit picks would delete the default core of every platform
    // the user never opened the picker for, which is most of them.
    @Test fun `a default nobody explicitly picked still counts as used`() {
        val defaults = mapOf("NDS" to "melondsds_libretro")
        val used = CoreUsage.usedCoreIds(
            platformTags = listOf("NDS"),
            coreMappingFor = { defaults[it].orEmpty() },
            overridesFor = { emptyList() },
        )
        assertTrue("melondsds_libretro" in used)
    }

    @Test fun `a per-game override counts even when no platform names the core`() {
        val used = CoreUsage.usedCoreIds(
            platformTags = listOf("PS"),
            coreMappingFor = { "swanstation_libretro" },
            overridesFor = { listOf(embedded("mednafen_psx_hw_libretro")) },
        )
        assertTrue("a core only one game asks for is still in use",
            "mednafen_psx_hw_libretro" in used)
    }

    @Test fun `a platform that names nothing contributes nothing`() {
        val used = CoreUsage.usedCoreIds(
            platformTags = listOf("3DS"),
            coreMappingFor = { "" },
            overridesFor = { emptyList() },
        )
        assertTrue(used.isEmpty())
    }

    @Test fun `an unused core is reported unused and its bytes are reclaimable`() {
        val rows = CoreUsage.rows(
            installed = listOf("snes9x_libretro", "geolith_libretro"),
            sizeOf = { if (it == "geolith_libretro") 900_000L else 4_000_000L },
            displayNameOf = { it },
            usedBy = { if (it == "snes9x_libretro") listOf("Super Nintendo") else emptyList() },
        )
        val geolith = rows.single { it.coreId == "geolith_libretro" }
        assertFalse(geolith.inUse)
        assertEquals(900_000L, CoreUsage.reclaimableBytes(rows))
    }

    // The removable ones sort last, so the list opens on what is safe to look at rather than on
    // the row the user is most likely to act on by accident.
    @Test fun `used cores sort before unused ones`() {
        val rows = CoreUsage.rows(
            installed = listOf("aaa_unused", "zzz_used"),
            sizeOf = { 1L },
            displayNameOf = { it },
            usedBy = { if (it == "zzz_used") listOf("Somewhere") else emptyList() },
        )
        assertEquals(listOf("zzz_used", "aaa_unused"), rows.map { it.coreId })
    }
}
