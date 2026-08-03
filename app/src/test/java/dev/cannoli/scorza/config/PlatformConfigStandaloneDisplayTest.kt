package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.ui.screens.EmulatorMappingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformConfigStandaloneDisplayTest {
    private fun config(): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return PlatformConfig(java.io.File(ctx.cacheDir, "sd-root").apply { mkdirs() }, ctx.assets)
    }

    @Test fun `standalone selection renders app name not bundled core`() {
        val pc = config()
        pc.setPlatformChoice("NES", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.explusalpha.NesEmu"))
        // Simulate RetroArch installed with nestopia_libretro so coreStatus returns "Present",
        // which is the scenario where the bug manifests (core branch fires instead of standalone).
        val installedRaCores = mapOf("org.libretro.retroarch" to setOf("nestopia_libretro"))
        val entry = pc.getDetailedMappings(installedRaCores = installedRaCores).first { it.tag == "NES" }
        assertEquals("Standalone", entry.runnerLabel)
        assertTrue(entry.coreDisplayName != pc.getCoreDisplayName("nestopia_libretro"))
    }

    @Test fun `a mapped core confirmed missing is flagged NOT_INSTALLED`() {
        val pc = config()
        // No installedRaCores and no unresponsive packages: coreStatus is "Missing" (confirmed absent).
        val entry = pc.getDetailedMappings().first { it.tag == "NES" }
        assertEquals(pc.getCoreDisplayName("nestopia_libretro"), entry.coreDisplayName)
        assertEquals(EmulatorMappingStatus.NOT_INSTALLED, entry.status)
    }

    @Test fun `a mapped core that is installed is READY`() {
        val pc = config()
        val installedRaCores = mapOf("org.libretro.retroarch" to setOf("nestopia_libretro"))
        val entry = pc.getDetailedMappings(installedRaCores = installedRaCores).first { it.tag == "NES" }
        assertEquals(pc.getCoreDisplayName("nestopia_libretro"), entry.coreDisplayName)
        assertEquals(EmulatorMappingStatus.READY, entry.status)
    }

    @Test fun `a mapped core on a RetroArch that cannot report cores is not flagged`() {
        val pc = config()
        // RA present but cannot report its cores (unresponsive): coreStatus "Unknown" -> show as picked.
        val entry = pc.getDetailedMappings(unresponsivePackages = setOf("org.libretro.retroarch"))
            .first { it.tag == "NES" }
        assertEquals(pc.getCoreDisplayName("nestopia_libretro"), entry.coreDisplayName)
        assertEquals(EmulatorMappingStatus.READY, entry.status)
    }

    // A pick now persists as part of making it, so there is no uncommitted window in which a
    // reload could discard it. The old two-step set-then-save was the source of the "who forgot
    // to call saveCoreMappings" bug class.
    @Test fun `a pick persists immediately and survives a reload`() {
        val pc = config()
        assertFalse(pc.hasUserMapping("NES"))
        pc.setPlatformChoice("NES", EmulatorChoice(EmulatorSource.RetroArch, "nestopia_libretro"))
        pc.reloadCoreMappings()
        assertTrue(pc.hasUserMapping("NES"))
        assertEquals(
            EmulatorChoice(EmulatorSource.RetroArch, "nestopia_libretro"),
            pc.getPlatformChoice("NES"),
        )
    }

    @Test fun `reset persists immediately too`() {
        val pc = config()
        pc.setPlatformChoice("NES", EmulatorChoice(EmulatorSource.RetroArch, "nestopia_libretro"))
        pc.resetPlatformToDefault(
            "NES", ApplicationProvider.getApplicationContext<android.content.Context>().packageManager,
        )
        pc.reloadCoreMappings()
        assertFalse(pc.hasUserMapping("NES"))
    }
}
