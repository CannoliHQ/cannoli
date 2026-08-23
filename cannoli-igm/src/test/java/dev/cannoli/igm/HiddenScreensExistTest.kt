package dev.cannoli.igm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A refusal that no longer matches anything is a screen quietly coming back.
 *
 * All Settings defers to RetroArch for structure, so the only thing Cannoli still names is what it
 * refuses to show, keyed by RetroArch's label strings. Those are not ours: a bump can rename or
 * remove a screen, and the refusal then silently stops applying. Nothing about that fails loudly,
 * the menu just grows a screen we decided not to have. This checks every entry still matches
 * something RetroArch actually has.
 */
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

    // A promoted key that no longer exists is worse than a refusal that no longer matches: the row
    // simply never renders, and the setting we cut its screen to keep is gone with no trace.
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
