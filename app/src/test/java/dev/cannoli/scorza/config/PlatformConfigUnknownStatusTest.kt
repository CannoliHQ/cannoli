package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.ui.screens.EmulatorMappingStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformConfigUnknownStatusTest {
    private fun config(): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return PlatformConfig(File(ctx.cacheDir, "unknown-root").apply { mkdirs() }, ctx.assets)
    }

    // Only an external RetroArch has an unknown state, because only its core list comes from a
    // query. The embedded runner reads a directory, so an unset platform is never unknown.
    private fun configOnRetroArch(): PlatformConfig = config().also {
        it.setPlatformChoice("NES", EmulatorChoice(EmulatorSource.RetroArch, "nestopia_libretro", RA))
    }

    private val RA = "com.retroarch"

    @Test fun `a core nobody can report is unknown, not not-installed`() {
        val entry = configOnRetroArch().detailedMappingFor(
            "NES", pm = null, installedRaCores = emptyMap(), embeddedCoresDir = null,
            unreportablePackages = setOf(RA),
        )
        assertEquals(EmulatorMappingStatus.UNKNOWN, entry.status)
    }

    @Test fun `a core confirmed absent stays not-installed`() {
        val entry = configOnRetroArch().detailedMappingFor(
            "NES", pm = null, installedRaCores = emptyMap(), embeddedCoresDir = null,
            unreportablePackages = emptySet(),
        )
        assertEquals(EmulatorMappingStatus.NOT_INSTALLED, entry.status)
    }

    // The embedded runner's cores are a file check, so a platform on it is confirmed absent
    // rather than unknown even while an external RetroArch is failing to report.
    @Test fun `an embedded core is never unknown`() {
        val entry = config().detailedMappingFor(
            "NES", pm = null, installedRaCores = emptyMap(), embeddedCoresDir = null,
            unreportablePackages = setOf(RA),
        )
        assertEquals(EmulatorMappingStatus.NOT_INSTALLED, entry.status)
    }
}
