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

    @Test fun `a core nobody can report is unknown, not not-installed`() {
        val entry = config().detailedMappingFor(
            "NES", pm = null, installedRaCores = emptyMap(), embeddedCoresDir = null,
            unreportablePackages = setOf("com.retroarch"),
        )
        assertEquals(EmulatorMappingStatus.UNKNOWN, entry.status)
    }

    @Test fun `a core confirmed absent stays not-installed`() {
        val entry = config().detailedMappingFor(
            "NES", pm = null, installedRaCores = emptyMap(), embeddedCoresDir = null,
            unreportablePackages = emptySet(),
        )
        assertEquals(EmulatorMappingStatus.NOT_INSTALLED, entry.status)
    }
}
