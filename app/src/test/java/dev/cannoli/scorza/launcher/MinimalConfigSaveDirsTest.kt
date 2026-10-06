package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class MinimalConfigSaveDirsTest {

    private fun config() = LaunchManager.buildMinimalConfig("/sd/Cannoli")

    private fun valueOf(key: String): String? =
        config().lineSequence()
            .firstOrNull { it.startsWith("$key ") }
            ?.substringAfter("=")
            ?.trim()
            ?.trim('"')

    @Test fun `the apk bundle is never extracted`() =
        assertEquals("false", valueOf("bundle_assets_extract_enable"))

    @Test fun `saves sort by content directory`() =
        assertEquals("true", valueOf("sort_savefiles_by_content_enable"))

    @Test fun `saves never sort by core`() =
        assertEquals("false", valueOf("sort_savefiles_enable"))

    @Test fun `states never sort by core`() =
        assertEquals("false", valueOf("sort_savestates_enable"))

    @Test fun `states sort by content directory`() =
        assertEquals("true", valueOf("sort_savestates_by_content_enable"))

    @Test fun `the save roots are the Cannoli directories`() {
        assertEquals("/sd/Cannoli/Saves", valueOf("savefile_directory"))
        assertEquals("/sd/Cannoli/Save States", valueOf("savestate_directory"))
    }

    @Test fun `RetroArch does not save its config on exit`() =
        assertEquals("false", valueOf("config_save_on_exit"))

    @Test fun `RetroArch reads the Cannoli autoconfig directory`() =
        assertEquals("/sd/Cannoli/Config/Input/Autoconfig", valueOf("joypad_autoconfig_dir"))
}

class MinimalConfigThumbnailTest {

    @Test fun `state screenshots are enabled`() {
        val value = LaunchManager.buildMinimalConfig("/sd/Cannoli")
            .lineSequence()
            .firstOrNull { it.startsWith("savestate_thumbnail_enable ") }
            ?.substringAfter("=")?.trim()?.trim('"')
        assertEquals("true", value)
    }
}
