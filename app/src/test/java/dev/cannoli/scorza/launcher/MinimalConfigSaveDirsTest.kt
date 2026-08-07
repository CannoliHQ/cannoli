package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RetroArch builds its save directory by appending the content directory name and then the core's
 * library_name, and both sort flags default to on. Cannoli wants the first level and never the
 * second, so leaving either flag unstated puts saves somewhere the launcher does not look.
 */
class MinimalConfigSaveDirsTest {

    private fun config() = LaunchManager.buildMinimalConfig("/sd/Cannoli")

    private fun valueOf(key: String): String? =
        config().lineSequence()
            .firstOrNull { it.startsWith("$key ") }
            ?.substringAfter("=")
            ?.trim()
            ?.trim('"')

    @Test fun `saves sort by content directory`() =
        assertEquals("true", valueOf("sort_savefiles_by_content_enable"))

    // The regression this guards: unstated, this defaults to true and appends the core name,
    // giving Saves/GBA/mGBA instead of Saves/GBA.
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

    // RetroArch must never write its own config back: Cannoli's store is authoritative and a
    // config written on exit would silently clobber whatever was generated here.
    @Test fun `RetroArch does not save its config on exit`() =
        assertEquals("false", valueOf("config_save_on_exit"))

    @Test fun `RetroArch reads the Cannoli autoconfig directory`() =
        assertEquals("/sd/Cannoli/Config/Input/Autoconfig", valueOf("input_autoconfig_directory"))
}

/**
 * RetroArch only defaults savestate_thumbnail_enable on for x86_64, so on every device Cannoli
 * ships to it is off unless stated. Without it RetroArch writes the state and no screenshot, and
 * the save and load rows show whatever stale image was left beside it.
 */
class MinimalConfigThumbnailTest {

    @Test fun `state screenshots are enabled`() {
        val value = LaunchManager.buildMinimalConfig("/sd/Cannoli")
            .lineSequence()
            .firstOrNull { it.startsWith("savestate_thumbnail_enable ") }
            ?.substringAfter("=")?.trim()?.trim('"')
        assertEquals("true", value)
    }
}
