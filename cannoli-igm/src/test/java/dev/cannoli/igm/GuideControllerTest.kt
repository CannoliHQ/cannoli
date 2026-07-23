package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuideControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun controllerWith(vararg names: String): Pair<GuideController, GuideManager> {
        val dir = File(tmp.root, "Guides/nes/Game").apply { mkdirs() }
        names.forEach { File(dir, it).writeText("body") }
        val manager = GuideManager(tmp.root.absolutePath, "nes", "Game")
        val c = GuideController()
        c.attach(manager)
        return c to manager
    }

    @Test fun attachPopulatesGuideFiles() {
        val (c, _) = controllerWith("a.txt", "b.txt")
        assertEquals(listOf("a.txt", "b.txt"), c.guideFiles.value.map { it.name })
    }

    @Test fun prepareGuideReturnsNullWithoutManager() {
        val c = GuideController()
        assertNull(c.prepareGuide(GuideFile(File(tmp.root, "x.txt"), GuideType.TXT)))
    }

    @Test fun prepareGuideSeedsSavedPositionForTxt() {
        val (c, manager) = controllerWith("a.txt")
        val guide = c.guideFiles.value.first()
        manager.save(guide.file, position = 42, scrollY = 0, scrollX = 5, zoom = 2)
        val open = c.prepareGuide(guide)!!
        assertEquals(guide.file.absolutePath, open.filePath)
        assertEquals(0, open.initialPage)
        assertEquals(2, open.textZoom)
        assertEquals(42, c.guideInitialScroll.intValue)
        assertEquals(5, c.guideInitialScrollX.intValue)
        assertEquals(0, c.guidePageCount.intValue)
    }

    @Test fun scrollAndPageJumpSetSignals() {
        val (c, _) = controllerWith("a.txt")
        c.scroll(1); assertEquals(1, c.guideScrollDir.intValue)
        c.scroll(0); assertEquals(0, c.guideScrollDir.intValue)
        c.scrollX(-1); assertEquals(-1, c.guideScrollXDir.intValue)
        val before = c.guidePageJump.intValue
        c.pageJump(1)
        assertEquals(1, c.guidePageJumpDir.intValue)
        assertEquals(before + 1, c.guidePageJump.intValue)
    }

    @Test fun saveGuidePersistsLiveScrollAndZoom() {
        val (c, manager) = controllerWith("a.txt")
        val guide = c.guideFiles.value.first()
        c.prepareGuide(guide)
        c.onScrollChanged(y = 130, x = 9)
        c.saveGuide(guide, pdfPage = null, textZoom = 3)
        val saved = manager.loadSavedPosition(guide.file)
        assertEquals(130, saved.position)
        assertEquals(130, saved.scrollY)
        assertEquals(9, saved.scrollX)
        assertEquals(3, saved.zoom)
    }
}
