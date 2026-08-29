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
        val c = testController(FakeRetroArchBridge())
        c.attachGuides(GuideManager(tmp.root.absolutePath, "nes", "Game"))
        return c
    }

    @Test fun noGuidesMeansHasGuidesFalse() {
        val c = testController(FakeRetroArchBridge())
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

    private fun openGuide(name: String = "only.txt"): IGMController {
        val c = controllerWithGuides(name)
        c.openMenu()
        val guideIndex = c.buildMenuOptions().guideIndex
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = guideIndex))
        c.handleKeyDown(96)
        return c
    }

    // The guide scrolls for as long as a direction is held. Nothing cleared that on release, so a
    // guide opened in game ran to the end of the document on its own.
    @Test fun `releasing a direction stops the guide scrolling`() {
        val c = openGuide()
        c.handleKeyDown(20)
        assertEquals(1, c.guideScrollDir.intValue)

        c.handleKeyUp(20)
        assertEquals(0, c.guideScrollDir.intValue)
    }

    // Text reflows rather than overflowing, so only a page that can be wider than the screen pans.
    @Test fun `releasing a direction stops the guide panning`() {
        val c = openGuide("only.png")
        c.replaceTop((c.currentScreen as IGMScreen.Guide).copy(textZoom = 2))
        c.handleKeyDown(22)
        assertEquals(1, c.guideScrollXDir.intValue)

        c.handleKeyUp(22)
        assertEquals(0, c.guideScrollXDir.intValue)
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
