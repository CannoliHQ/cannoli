package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuideManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun guidesDir(root: File, tag: String, title: String): File =
        File(root, "Guides/$tag/$title").apply { mkdirs() }

    @Test
    fun findGuidesFiltersByExtensionAndSortsByName() {
        val root = tmp.root
        val dir = guidesDir(root, "nes", "Game")
        File(dir, "b.txt").writeText("x")
        File(dir, "A.pdf").writeText("x")
        File(dir, "shot.PNG").writeText("x")
        File(dir, "notes.md").writeText("x")

        val guides = GuideManager(root.absolutePath, "nes", "Game").findGuides()

        assertEquals(listOf("A.pdf", "b.txt", "shot.PNG"), guides.map { it.name })
        assertEquals(GuideType.PDF, guides[0].type)
        assertEquals(GuideType.TXT, guides[1].type)
        assertEquals(GuideType.IMAGE, guides[2].type)
    }

    @Test
    fun findGuidesReturnsEmptyWhenDirMissing() {
        val guides = GuideManager(tmp.root.absolutePath, "nes", "Nothing").findGuides()
        assertEquals(emptyList<GuideFile>(), guides)
    }

    @Test
    fun savedPositionRoundTrips() {
        val root = tmp.root
        val dir = guidesDir(root, "nes", "Game")
        val file = File(dir, "A.pdf").apply { writeText("x") }
        val gm = GuideManager(root.absolutePath, "nes", "Game")

        gm.save(file, position = 5, scrollY = 120, scrollX = 7, zoom = 3)

        val saved = gm.loadSavedPosition(file)
        assertEquals(5, saved.position)
        assertEquals(120, saved.scrollY)
        assertEquals(7, saved.scrollX)
        assertEquals(3, saved.zoom)
        assertEquals(true, File(root, "Config/State/guide_positions.ini").exists())
    }

    @Test
    fun loadSavedPositionDefaultsWhenAbsent() {
        val root = tmp.root
        val dir = guidesDir(root, "nes", "Game")
        val file = File(dir, "A.pdf").apply { writeText("x") }
        val saved = GuideManager(root.absolutePath, "nes", "Game").loadSavedPosition(file)
        assertEquals(GuideManager.SavedPosition(), saved)
    }
}
