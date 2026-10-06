package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformConfigResetTest {
    private fun config(): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return PlatformConfig(File(ctx.cacheDir, "reset-root").apply { mkdirs() }, ctx.assets)
    }

    @Test fun `reset with no resolvable default leaves the platform unmapped`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pc = config()
        pc.setPlatformChoice("NES", EmulatorChoice(EmulatorSource.Embedded, "nestopia_libretro"))
        assertTrue(pc.hasUserMapping("NES"))
        pc.resetPlatformToDefault("NES", ctx.packageManager)
        assertFalse(pc.hasUserMapping("NES"))
    }
}
