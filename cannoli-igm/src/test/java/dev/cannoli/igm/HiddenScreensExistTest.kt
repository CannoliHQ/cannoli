package dev.cannoli.igm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HiddenScreensExistTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "scripts/ra-menu-screens.py").isFile }

    private val screenTable: String by lazy {
        File(repoRoot, "ricotta/jni/ricotta_menu_screens.h").readText()
    }

    private val censusKeys: Set<String> by lazy {
        File(repoRoot, "app/src/test/resources/ra-settings-census.tsv")
            .readLines().drop(1).mapNotNull { it.split("\t").firstOrNull() }.toSet()
    }

    @Test
    fun `every hidden screen is still a screen RetroArch builds`() {
        val gone = HIDDEN_SCREENS.filterNot { screenTable.contains("\"$it\"") }
        assertTrue(
            "these are refused but RetroArch no longer has them, so the refusal does nothing " +
                "and the screen may have reappeared under a new name:\n" +
                gone.joinToString("\n") { "  $it" },
            gone.isEmpty(),
        )
    }

    @Test
    fun `every promoted key is still a setting RetroArch registers`() {
        val gone = PROMOTED_KEYS.values.flatten().filterNot { it in censusKeys }
        assertTrue(
            "these are promoted onto a screen to survive their own screen being cut, but " +
                "RetroArch no longer registers them, so they are simply lost:\n" +
                gone.joinToString("\n") { "  $it" },
            gone.isEmpty(),
        )
    }

    @Test
    fun `every hidden key is still a setting RetroArch registers`() {
        val gone = HIDDEN_KEYS.filterNot { it in censusKeys }
        assertTrue(
            "these are refused but RetroArch no longer registers them under that name:\n" +
                gone.joinToString("\n") { "  $it" },
            gone.isEmpty(),
        )
    }
}
