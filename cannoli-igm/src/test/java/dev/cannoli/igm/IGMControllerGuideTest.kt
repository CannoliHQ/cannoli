package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IGMControllerGuideTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun controllerWithGuides(vararg names: String): IGMController {
        val dir = File(tmp.root, "Guides/nes/Game").apply { mkdirs() }
        names.forEach { File(dir, it).writeText("body") }
        val c = IGMController(FakeEmulatorBridge(), "Game")
        c.attachGuides(GuideManager(tmp.root.absolutePath, "nes", "Game"))
        return c
    }

    @Test fun noGuidesMeansHasGuidesFalse() {
        val c = IGMController(FakeEmulatorBridge(), "Game")
        c.attachGuides(GuideManager(tmp.root.absolutePath, "nes", "Empty"))
        assertFalse(c.buildMenuOptions().hasGuides)
    }

    @Test fun guidesPresentMeansHasGuidesTrue() {
        val c = controllerWithGuides("a.txt")
        assertTrue(c.buildMenuOptions().hasGuides)
        assertEquals(1, c.guideFiles.value.size)
    }

    @Test fun singleGuideActionOpensGuideDirectly() {
        val c = controllerWithGuides("only.txt")
        c.openMenu()
        val guideIndex = c.buildMenuOptions().guideIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = guideIndex))
        c.handleKeyDown(96)
        assertTrue(c.currentScreen is IGMScreen.Guide)
    }

    @Test fun multipleGuidesActionOpensPicker() {
        val c = controllerWithGuides("a.txt", "b.txt")
        c.openMenu()
        val guideIndex = c.buildMenuOptions().guideIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = guideIndex))
        c.handleKeyDown(96)
        assertTrue(c.currentScreen is IGMScreen.GuidePicker)
        c.handleKeyDown(20)
        assertEquals(1, (c.currentScreen as IGMScreen.GuidePicker).selectedIndex)
    }
}
