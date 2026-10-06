package dev.cannoli.ricotta

import dev.cannoli.igm.CuratedCatalog
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CuratedLabelWiringTest {

    private val overlay =
        File("src/main/java/com/retroarch/browser/retroactivity/IGMOverlay.kt")

    private val rows = CuratedCatalog.categories.flatMap { it.rows }

    @Test fun `the overlay source is where the test thinks it is`() {
        assertTrue("expected ${overlay.absolutePath} to exist", overlay.exists())
    }

    @Test fun `the shipped map names every curated row and preset`() {
        val text = overlay.readText()
        val missing = (rows.map { it.key } + rows.flatMap { r -> r.presets.map { it.labelKey } })
            .filterNot { text.contains("\"$it\"") }

        assertTrue("IGMOverlay has no label wired for: $missing", missing.isEmpty())
    }
}
