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

    @Test fun `RetroArch is available whenever the platform has candidate cores`() {
        val sources = config().availableSources("NES")
        assertTrue(EmulatorSource.RetroArch in sources)
    }

    @Test fun `a platform with no standalone apps does not list Standalone`() {
        val sources = config().availableSources("32X")
        assertFalse(EmulatorSource.Standalone in sources)
    }

    @Test fun `installed-only Internal options omit unbundled cores and includeAll surfaces them as unavailable`() {
        val pc = config()
        val installedOnly = pc.emulatorOptionsForSource("NES", EmulatorSource.Internal, includeAll = false)
        val all = pc.emulatorOptionsForSource("NES", EmulatorSource.Internal, includeAll = true)
        assertTrue(all.size >= installedOnly.size)
        all.filter { opt -> installedOnly.none { it.coreId == opt.coreId } }
            .forEach { assertFalse(it.available) }
    }

    @Test fun `RA is unresponsive only when nothing reports and a package is unresponsive`() {
        val pc = config()
        val candidate = "nestopia_libretro"
        assertFalse(pc.isRetroArchUnresponsive("NES", emptyMap(), emptySet()))
        assertTrue(pc.isRetroArchUnresponsive("NES", emptyMap(), setOf("com.retroarch")))
        assertFalse(pc.isRetroArchUnresponsive("NES", mapOf("com.retroarch" to setOf(candidate)), emptySet()))
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
        val missing = pc.getFirmwareStatus(coreId, biosDir)
        assertTrue(missing.isNotEmpty())
        assertTrue("no firmware files present yet", missing.all { !it.second })

        val firstPath = expected.first().path
        File(biosDir, firstPath).apply { parentFile?.mkdirs() }.writeText("stub")
        val afterPlacing = pc.getFirmwareStatus(coreId, biosDir)
        assertTrue("placed firmware is reported present",
            afterPlacing.first { it.first.path == firstPath }.second)
    }
}
