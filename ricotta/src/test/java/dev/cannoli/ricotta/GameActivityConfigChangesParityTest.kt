package dev.cannoli.ricotta

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameActivityConfigChangesParityTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun gameActivity(): String {
        val start = manifest.indexOf("android:name=\"com.retroarch.browser.retroactivity.RetroActivityFuture\"")
        assertTrue("RetroActivityFuture entry missing", start >= 0)
        return manifest.substring(start, manifest.indexOf("/>", start))
    }

    private fun configChanges(): Set<String> {
        val m = Regex("android:configChanges=\"([^\"]*)\"").find(gameActivity())
        assertTrue("RetroActivityFuture declares no configChanges", m != null)
        return m!!.groupValues[1].split('|').toSet()
    }

    @Test fun `a density change is handled in place rather than relaunching the game`() {
        assertTrue(configChanges().contains("density"))
    }

    @Test fun `every change upstream handles is still handled`() {
        val upstream = setOf(
            "mcc", "mnc", "locale", "touchscreen", "keyboard", "keyboardHidden", "navigation",
            "orientation", "screenLayout", "uiMode", "screenSize", "smallestScreenSize", "fontScale",
        )
        assertTrue((upstream - configChanges()).toString(), configChanges().containsAll(upstream))
    }

    @Test fun `the list replaces upstream's rather than failing the merge`() {
        val replace = Regex("tools:replace=\"([^\"]*)\"").find(gameActivity())!!.groupValues[1]
        assertTrue(replace.split(',').contains("android:configChanges"))
    }
}
