package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
