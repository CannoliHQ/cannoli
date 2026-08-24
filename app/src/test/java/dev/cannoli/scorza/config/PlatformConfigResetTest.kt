package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    // No bundled cores and no installed apps in this fixture, so the default resolves to
    // nothing and the platform is left unmapped.
    @Test fun `reset with no resolvable default leaves the platform unmapped`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pc = config()
        pc.setPlatformChoice("NES", EmulatorChoice(EmulatorSource.Embedded, "nestopia_libretro"))
        assertTrue(pc.hasUserMapping("NES"))
        pc.resetPlatformToDefault("NES", ctx.packageManager)
        assertFalse(pc.hasUserMapping("NES"))
    }

    // Per-game overrides moved out of PlatformConfig into the game_overrides table. The clearing
    // and per-platform listing this file used to cover now live in GameOverrideStoreTest, against
    // a real database, which additionally covers the delete cascade and the GB/GBA prefix case
    // this path-keyed version could not reach.
}
