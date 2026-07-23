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

    @Test fun `reset clears user core runner app and reports no user mapping`() {
        val pc = config()
        pc.setCoreMapping("NES", "nestopia_libretro", "RetroArch")
        assertTrue(pc.hasUserMapping("NES"))
        pc.resetPlatformMapping("NES")
        assertFalse(pc.hasUserMapping("NES"))
    }

    @Test fun `clearGameOverride removes a single override and getPlatformOverrides reflects it`() {
        val pc = config()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val romsDir = File(File(ctx.cacheDir, "reset-root"), "Roms")
        val tagDir = File(romsDir, "NES")
        val path = File(tagDir, "game.nes").absolutePath
        pc.setGameOverride(path, "nestopia_libretro", "RetroArch")
        assertEquals(1, pc.getPlatformOverrides("NES").size)
        pc.clearGameOverride(path)
        assertEquals(0, pc.getPlatformOverrides("NES").size)
    }
}
