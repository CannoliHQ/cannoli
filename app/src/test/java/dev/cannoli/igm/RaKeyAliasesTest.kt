package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the generated menu-name to config-key aliases.
 *
 * A RetroArch setting has two names: menu_setting_find() matches the menu label, config_file reads
 * and writes the config key, and for 68 settings they differ. ricotta_ra_save_override looks a
 * setting up by menu name, so without translating it writes a key RetroArch never reads back and
 * the override silently does not apply on the next launch.
 *
 * The table is checked in and only regenerates when the script runs, so a bump can leave it
 * describing names that have changed. Here it is checked against the current submodule.
 */
class RaKeyAliasesTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "scripts/ra-key-aliases.py").isFile }

    private val generated: String by lazy {
        File(repoRoot, "ricotta/jni/ricotta_key_aliases.h").readText()
    }

    private fun regenerate(): String {
        val p = ProcessBuilder("python3", "scripts/ra-key-aliases.py")
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return text
    }

    @Test
    fun `the generated table is present and plausible`() {
        val rows = generated.lines().count { it.trimStart().startsWith("{ \"") }
        assertTrue("alias table looks truncated: $rows rows", rows > 30)
    }

    // The ones a player would actually change from the IGM, and so the ones whose silent revert
    // would be noticed. Each was verified against configuration.c by hand.
    @Test
    fun `the aliases that reach the IGM today are present`() {
        for ((menu, config) in listOf(
            "audio_output_rate" to "audio_out_rate",
            "gpu_index" to "vulkan_gpu_index",
            "game_mode_enable" to "gamemode_enable",
            "video_viewport_custom_width" to "custom_viewport_width",
            "video_viewport_custom_height" to "custom_viewport_height",
        )) {
            assertTrue(
                "$menu -> $config missing from the generated alias table",
                generated.contains("{ \"$menu\", \"$config\" },"),
            )
        }
    }

    @Test
    fun `the checked-in table matches what the current RetroArch source generates`() {
        assertEquals(
            "ricotta_key_aliases.h is stale. Regenerate with:\n" +
                "  python3 scripts/ra-key-aliases.py ricotta/jni/ricotta_key_aliases.h",
            regenerate().trim(),
            generated.trim(),
        )
    }
}
