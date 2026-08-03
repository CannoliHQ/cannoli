package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.ui.screens.EmulatorMappingStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The "Platform Setting (X)" row in the game context menu names whatever the platform is
// mapped to, which is a standalone app as often as a core.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformSettingLabelTest {
    private fun config(): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return PlatformConfig(java.io.File(ctx.cacheDir, "sd-root").apply { mkdirs() }, ctx.assets)
    }

    private fun label(pc: PlatformConfig, tag: String) = pc.detailedMappingFor(tag).coreDisplayName

    @Test fun `standalone pick on a platform with no default core names the picked app`() {
        val pc = config()
        pc.setPlatformChoice("GC", EmulatorChoice(EmulatorSource.Standalone, appPackage = "org.dolphinemu.mmjr"))
        assertEquals("Dolphin MMJR2", label(pc, "GC"))
    }

    @Test fun `a different standalone pick on the same platform is distinguished`() {
        val pc = config()
        pc.setPlatformChoice("GC", EmulatorChoice(EmulatorSource.Standalone, appPackage = "dev.cannoli.delfino"))
        assertEquals("Delfino", label(pc, "GC"))
    }

    @Test fun `standalone pick on a platform with a default core names the app not the core`() {
        val pc = config()
        pc.setPlatformChoice("NES", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.explusalpha.NesEmu"))
        assertEquals("NES.emu", label(pc, "NES"))
    }

    @Test fun `a core pick still names the core`() {
        val pc = config()
        pc.setPlatformChoice("NES", EmulatorChoice(EmulatorSource.RetroArch, "fceumm_libretro"))
        assertEquals(pc.getCoreDisplayName("fceumm_libretro"), label(pc, "NES"))
    }

    @Test fun `standalone with no app installed reports needs setup`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pc = config()
        pc.setPlatformChoice("GC", EmulatorChoice(EmulatorSource.Standalone))
        val entry = pc.detailedMappingFor("GC", ctx.packageManager)
        assertEquals(EmulatorMappingStatus.NEEDS_SETUP, entry.status)
        assertEquals("Needs setup", entry.coreDisplayName)
    }
}
