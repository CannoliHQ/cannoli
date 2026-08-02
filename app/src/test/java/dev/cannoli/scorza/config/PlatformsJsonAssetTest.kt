package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun `bundled PC platform launches GameNative from the file contents`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = PlatformConfig(File(ctx.cacheDir, "fake-root-pc"), ctx.assets)

        assertTrue("PC" in config.getAllTags())
        assertNull(config.getCoreName("PC"))

        val app = config.getAppOptions("PC").single()
        assertEquals("app.gamenative", app.packageName)
        assertEquals("app.gamenative.MainActivity", app.activity)
        assertEquals("app.gamenative.LAUNCH_GAME", app.action)
        assertTrue(app.data is DataBinding.None)

        val appId = app.extras.single { it.key == "app_id" }
        assertEquals(ExtraValueKind.INT, appId.kind)
        assertEquals("{rom_contents}", appId.value)

        val source = app.extras.single { it.key == "game_source" }
        assertEquals(ExtraValueKind.STRING, source.kind)
        assertEquals("{rom_extension}", source.value)
        assertEquals(
            mapOf(
                "steam" to "STEAM",
                "epic" to "EPIC",
                "gog" to "GOG",
                "amazon" to "AMAZON",
                "pcgame" to "CUSTOM_GAME",
            ),
            source.map,
        )
    }
}
