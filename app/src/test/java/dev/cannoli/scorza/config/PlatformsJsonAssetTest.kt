package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformsJsonAssetTest {
    @Test fun `bundled platforms_json parses without throwing`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pc = PlatformConfig(File(ctx.cacheDir, "fake-root"), ctx.assets)
        check(pc.getAllTags().isNotEmpty())
    }

    @Test fun `Citra MMJ uses its exported game path launch contract`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pc = PlatformConfig(File(ctx.cacheDir, "citra-mmj-root"), ctx.assets)
        val config = pc.getAppOptions("3DS").first { it.packageName == "org.citra.emu" }

        assertEquals("org.citra.emu.ui.EmulationActivity", config.activity)
        assertEquals(1, config.extras.size)
        assertEquals("GamePath", config.extras.single().key)
        assertEquals(ExtraValueKind.FILE_PATH, config.extras.single().kind)
    }
}
