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
}
