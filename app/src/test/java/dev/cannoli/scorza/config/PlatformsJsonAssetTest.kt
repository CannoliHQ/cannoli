package dev.cannoli.scorza.config

import android.content.Intent
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

        val app = config.getAppOptions("PC").single { it.packageName == "app.gamenative" }
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

    @Test fun `bundled PC platform offers GameHub Lite as a second launcher`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = PlatformConfig(File(ctx.cacheDir, "fake-root-pc-hub"), ctx.assets)

        val apps = config.getAppOptions("PC")
        assertEquals(2, apps.size)

        val hub = apps.single { it.packageName == "gamehub.lite" }
        assertEquals("com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity", hub.activity)
        assertEquals("gamehub.lite.LAUNCH_GAME", hub.action)
        assertTrue(hub.data is DataBinding.None)

        val steamAppId = hub.extras.single { it.key == "steamAppId" }
        assertEquals(ExtraValueKind.STRING, steamAppId.kind)
        assertEquals("{rom_contents}", steamAppId.value)

        // GameHub opens the game's detail page without this and waits for a tap on Play Now.
        val autoStart = hub.extras.single { it.key == "autoStartGame" }
        assertEquals(ExtraValueKind.BOOL, autoStart.kind)
        assertEquals("true", autoStart.value)

        // NO_HISTORY drops the detail activity once it hands off to the emulator, so quitting the
        // game empties GameHub's task and falls back to the launcher rather than its detail page.
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY,
            hub.intentFlags,
        )
    }
}
