package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the generated screen table the IGM descends through.
 *
 * All Settings defers to RetroArch for structure, which means building a specific displaylist per
 * screen, which needs that screen's DISPLAYLIST_* constant. RetroArch only knows it through four
 * static tables across two files, so scripts/ra-menu-screens.py composes them into
 * ricotta/jni/ricotta_menu_screens.h. That file is checked in and only regenerates when the script
 * is run, so a RetroArch bump can leave it describing a tree that no longer exists. Here it is
 * checked against the current submodule.
 */
class RaMenuScreensTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "scripts/ra-menu-screens.py").isFile }

    private val generated: String by lazy {
        File(repoRoot, "ricotta/jni/ricotta_menu_screens.h").readText()
    }

    private fun regenerate(): String {
        val p = ProcessBuilder("python3", "scripts/ra-menu-screens.py")
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
        assertTrue("screen table looks truncated: $rows rows", rows > 40)
    }

    // Without these the IGM cannot open the screens it most needs, and the failure is a row that
    // does nothing rather than a crash.
    @Test
    fun `the screens All Settings relies on are present`() {
        for (label in listOf(
            "video_settings",
            "video_output_settings",
            "video_scaling_settings",
            "video_synchronization_settings",
            "audio_settings",
            "latency_settings",
            "core_settings",
            "frame_throttle_settings",
        )) {
            assertTrue("$label missing from the generated screen table", generated.contains("\"$label\""))
        }
    }

    // The one that catches a bump: RetroArch renamed, moved or removed a screen and the checked-in
    // table still describes the old tree.
    @Test
    fun `the checked-in table matches what the current RetroArch source generates`() {
        assertEquals(
            "ricotta_menu_screens.h is stale. Regenerate with:\n" +
                "  python3 scripts/ra-menu-screens.py ricotta/jni/ricotta_menu_screens.h",
            regenerate().trim(),
            generated.trim(),
        )
    }
}
