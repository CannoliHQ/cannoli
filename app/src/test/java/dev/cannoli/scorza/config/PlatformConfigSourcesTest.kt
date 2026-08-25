package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.ui.screens.CoreAvailability
import org.junit.Assert.assertEquals
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
    private val RA = "com.retroarch"

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
        assertTrue("no firmware files present yet", missing.all { !it.second })

        val firstPath = expected.first().path
        File(biosDir, firstPath).apply { parentFile?.mkdirs() }.writeText("stub")
        val afterPlacing = pc.getFirmwareStatus("ATARI5200", coreId, biosDir)
        assertTrue("placed firmware is reported present",
            afterPlacing.first { it.first.path == firstPath }.second)
    }
}
