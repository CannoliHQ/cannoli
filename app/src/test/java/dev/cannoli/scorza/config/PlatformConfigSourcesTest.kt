package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformConfigSourcesTest {
    private fun config(): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return PlatformConfig(File(ctx.cacheDir, "src-root").apply { mkdirs() }, ctx.assets)
    }

    @Test fun `the embedded runner is available whenever the platform has candidate cores`() {
        val sources = config().availableSources("NES")
        assertTrue(EmulatorSource.Embedded in sources)
    }

    @Test fun `a platform with no standalone apps does not list Standalone`() {
        val sources = config().availableSources("32X")
        assertFalse(EmulatorSource.Standalone in sources)
    }

    @Test fun `getFirmwareStatus reports presence per firmware entry against the bios dir`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val coreInfo = CoreInfoRepository(ctx.assets)
        coreInfo.load()
        val pc = PlatformConfig(File(ctx.cacheDir, "fw-root").apply { mkdirs() }, ctx.assets, coreInfo)

        val coreId = "a5200_libretro"
        val expected = coreInfo.getFirmwareFor(coreId)
        assertTrue("a5200 core_info should declare firmware", expected.isNotEmpty())

        val biosDir = File(ctx.cacheDir, "fw-bios").apply { mkdirs() }
        val missing = pc.getFirmwareStatus("ATARI5200", coreId, biosDir)
        assertTrue(missing.isNotEmpty())
        // Presence, not satisfaction: a5200's entries are optional, so an absent one is satisfied
        // while still being absent, and asking the wrong question passed an empty BIOS folder.
        assertTrue(
            "no firmware files present yet",
            missing.filterIsInstance<FirmwareRequirement.Single>().none { it.present },
        )

        val firstPath = expected.first().path
        File(biosDir, firstPath).apply { parentFile?.mkdirs() }.writeText("stub")
        val afterPlacing = pc.getFirmwareStatus("ATARI5200", coreId, biosDir)
        assertTrue("placed firmware is reported present",
            afterPlacing.filterIsInstance<FirmwareRequirement.Single>()
                .first { it.entry.path == firstPath }.present)
    }
}
