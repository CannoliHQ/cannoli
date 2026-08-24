package dev.cannoli.scorza.config

import android.content.Intent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformConfigParserTest {

    private fun parse(json: String): AppConfig =
        PlatformConfig.parseAppConfigForTest(JSONObject(json))

    @Test fun `bare package only`() {
        val cfg = parse("""{"package":"com.example"}""")
        assertEquals("com.example", cfg.packageName)
        assertNull(cfg.activity)
        assertEquals(Intent.ACTION_VIEW, cfg.action)
        assertTrue(cfg.data is DataBinding.None)
        assertTrue(cfg.extras.isEmpty())
        assertEquals("*/*", cfg.mimeType)
        assertEquals(LaunchMethod.INTENT, cfg.launchMethod)
    }

    @Test fun `nethersx2 entry parses`() {
        val cfg = parse("""
            {
              "package": "xyz.aethersx2.android",
              "activity": "xyz.aethersx2.android.EmulationActivity",
              "action": "android.intent.action.MAIN",
              "extras": [{"key": "bootPath", "kind": "uri_string"}],
              "launchMethod": "intent"
            }
        """.trimIndent())
        assertEquals("xyz.aethersx2.android", cfg.packageName)
        assertEquals("xyz.aethersx2.android.EmulationActivity", cfg.activity)
        assertEquals(Intent.ACTION_MAIN, cfg.action)
        assertEquals(1, cfg.extras.size)
        assertEquals("bootPath", cfg.extras[0].key)
        assertEquals(ExtraValueKind.FILE_URI_STRING, cfg.extras[0].kind)
        assertEquals(LaunchMethod.INTENT, cfg.launchMethod)
    }

    @Test fun `delfino entry parses the delfino launch method`() {
        val cfg = parse("""{"package":"dev.cannoli.delfino","launchMethod":"delfino"}""")
        assertEquals(LaunchMethod.DELFINO, cfg.launchMethod)
    }

    @Test fun `file_provider data with grant default true`() {
        val cfg = parse("""{"package":"com.sky.SkyEmu","data":{"kind":"file_provider"}}""")
        val data = cfg.data as DataBinding.FileProvider
        assertTrue(data.grantPermission)
    }

    @Test fun `extras list parses uri_parcelable kind`() {
        val cfg = parse("""
            {"package":"me.magnum.melonds",
             "extras":[{"key":"uri","kind":"uri_parcelable"}]}
        """.trimIndent())
        assertEquals(ExtraValueKind.FILE_URI_PARCELABLE, cfg.extras[0].kind)
    }

    @Test fun `gamenative entry parses int and mapped string extras`() {
        val cfg = parse("""
            {
              "package": "app.gamenative",
              "activity": "app.gamenative.MainActivity",
              "action": "app.gamenative.LAUNCH_GAME",
              "extras": [
                { "key": "app_id", "kind": "int", "value": "{rom_contents}" },
                { "key": "game_source", "kind": "string", "value": "{rom_extension}",
                  "map": { "steam": "STEAM", "pcgame": "CUSTOM_GAME" } }
              ]
            }
        """.trimIndent())
        assertEquals("app.gamenative.LAUNCH_GAME", cfg.action)
        assertEquals(ExtraValueKind.INT, cfg.extras[0].kind)
        assertEquals("{rom_contents}", cfg.extras[0].value)
        assertNull(cfg.extras[0].map)
        assertEquals(ExtraValueKind.STRING, cfg.extras[1].kind)
        assertEquals("{rom_extension}", cfg.extras[1].value)
        assertEquals(mapOf("steam" to "STEAM", "pcgame" to "CUSTOM_GAME"), cfg.extras[1].map)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `int extra without value throws`() {
        parse("""{"package":"com.example","extras":[{"key":"app_id","kind":"int"}]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `string extra without value throws`() {
        parse("""{"package":"com.example","extras":[{"key":"src","kind":"string"}]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown extra kind throws`() {
        parse("""{"package":"com.example","extras":[{"key":"k","kind":"weird"}]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown data kind throws`() {
        parse("""{"package":"com.example","data":{"kind":"saf"}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing package throws`() {
        parse("""{"action":"android.intent.action.VIEW"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown launchMethod throws`() {
        parse("""{"package":"com.example","launchMethod":"fork"}""")
    }
}
