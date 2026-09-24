package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KEY_UP = 19
private const val KEY_DOWN = 20
private const val KEY_SOUTH = 96
private const val KEY_EAST = 97
private const val KEY_WEST = 99
private const val KEY_NORTH = 100
private const val KEY_MENU = 82

/** An Input category with only a Button Mappings row, for the screen's own provider-nav test. */
private class InputCategoryProvider : IgmSettingsProvider {
    override fun screen(path: List<String>): GenericIgmSettingsScreen = when (path) {
        emptyList<String>() -> GenericIgmSettingsScreen(
            "Settings",
            listOf(GenericIgmSettingsItem.Category(CuratedCatalog.CATEGORY_INPUT, "Input")),
        )
        listOf(CuratedCatalog.CATEGORY_INPUT) -> GenericIgmSettingsScreen(
            "Input",
            listOf(GenericIgmSettingsItem.Category(CuratedCatalog.INPUT_BUTTONS, "Button Mappings")),
        )
        else -> GenericIgmSettingsScreen("", emptyList())
    }
    override fun cycle(itemKey: String, direction: Int) {}
    override fun activate(itemKey: String): IgmSettingsExit.Prompt? = null
    override fun exitPrompt(): IgmSettingsExit = IgmSettingsExit.Close
    override fun setOnChanged(callback: () -> Unit) {}
}

private class InputCategoryBridge : FakeRetroArchBridge() {
    override fun settingsProvider(): IgmSettingsProvider = InputCategoryProvider()
}

class IGMButtonMappingsTest {

    private val mapping = IgmInputMapping(
        buttonKeycodes = mapOf(
            CanonicalButton.BTN_UP to listOf(KEY_UP),
            CanonicalButton.BTN_DOWN to listOf(KEY_DOWN),
            CanonicalButton.BTN_SOUTH to listOf(KEY_SOUTH),
            CanonicalButton.BTN_EAST to listOf(KEY_EAST),
            CanonicalButton.BTN_WEST to listOf(KEY_WEST),
            CanonicalButton.BTN_NORTH to listOf(KEY_NORTH),
            CanonicalButton.BTN_MENU to listOf(KEY_MENU),
        ),
        menuConfirm = CanonicalButton.BTN_SOUTH,
        menuBack = CanonicalButton.BTN_EAST,
    )

    private fun open(bridge: FakeRetroArchBridge = FakeRetroArchBridge()): IGMController =
        testController(bridge).apply {
            setInputMapping(mapping)
            openMenu()
            openButtonMappings()
        }

    private fun screen(c: IGMController) = c.currentScreen as IGMScreen.ButtonMappings

    @Test fun `the screen opens on the first row with nothing listening`() {
        val c = open()
        assertEquals(0, screen(c).selectedIndex)
        assertFalse(screen(c).listening)
    }

    @Test fun `down moves a row and wraps at the end`() {
        val c = open()
        c.handleKeyDown(KEY_DOWN)
        assertEquals(1, screen(c).selectedIndex)
        repeat(RemapButton.entries.size - 1) { c.handleKeyDown(KEY_DOWN) }
        assertEquals(0, screen(c).selectedIndex)
    }

    @Test fun `confirm listens and the next press binds that row`() {
        val bridge = FakeRetroArchBridge()
        val c = open(bridge)
        c.handleKeyDown(KEY_SOUTH)
        assertTrue(screen(c).listening)
        c.handleKeyDown(KEY_NORTH)
        assertFalse(screen(c).listening)
        assertEquals(RemapButton.NORTH.id, bridge.remap[RemapButton.UP.id])
    }

    @Test fun `the menu button cancels listening and binds nothing`() {
        val bridge = FakeRetroArchBridge()
        val c = open(bridge)
        c.handleKeyDown(KEY_SOUTH)
        c.handleKeyDown(KEY_MENU)
        assertFalse(screen(c).listening)
        assertTrue(bridge.remapSets.isEmpty())
    }

    @Test fun `north clears a row to unbound`() {
        val bridge = FakeRetroArchBridge()
        val c = open(bridge)
        c.handleKeyDown(KEY_NORTH)
        assertEquals(ButtonRemap.UNBOUND, bridge.remap[RemapButton.UP.id])
    }

    @Test fun `clearing a row that is already unbound stages nothing`() {
        val bridge = FakeRetroArchBridge()
        val c = open(bridge)
        c.handleKeyDown(KEY_NORTH)
        c.handleKeyDown(KEY_NORTH)
        assertEquals(1, bridge.remapSets.size)
    }

    @Test fun `west puts every row back to its default`() {
        val bridge = FakeRetroArchBridge()
        bridge.remap[RemapButton.UP.id] = RemapButton.DOWN.id
        val c = open(bridge)
        c.handleKeyDown(KEY_WEST)
        assertEquals(1, bridge.remapResets)
        assertTrue(ButtonRemap.isDefault(bridge.remap))
    }

    @Test fun `west does nothing when nothing is remapped`() {
        val bridge = FakeRetroArchBridge()
        val c = open(bridge)
        c.handleKeyDown(KEY_WEST)
        assertTrue(bridge.remapSets.isEmpty())
    }

    @Test fun `west is not offered when every row is on its routed default`() {
        val bridge = FakeRetroArchBridge()
        bridge.remapBase = ButtonRemap.identity() + (RemapButton.EAST.id to RemapButton.WEST.id)
        bridge.remap[RemapButton.EAST.id] = RemapButton.WEST.id
        val c = open(bridge)
        c.handleKeyDown(KEY_WEST)
        assertEquals(0, bridge.remapResets)
    }

    @Test fun `west resets a row that moved off its routed default`() {
        val bridge = FakeRetroArchBridge()
        bridge.remapBase = ButtonRemap.identity() + (RemapButton.EAST.id to RemapButton.WEST.id)
        bridge.remap[RemapButton.EAST.id] = RemapButton.NORTH.id
        val c = open(bridge)
        c.handleKeyDown(KEY_WEST)
        assertEquals(1, bridge.remapResets)
    }

    @Test fun `binding to a routed key stages what it sends, not the slot it sits on`() {
        val bridge = FakeRetroArchBridge()
        bridge.remapBase = ButtonRemap.identity() + (RemapButton.EAST.id to RemapButton.WEST.id)
        val c = open(bridge)
        c.handleKeyDown(KEY_SOUTH)
        c.handleKeyDown(KEY_EAST)
        assertEquals(RemapButton.WEST.id, bridge.remap[RemapButton.UP.id])
    }

    @Test fun `opening the screen reads the core's button names`() {
        val bridge = FakeRetroArchBridge()
        bridge.descriptors = mapOf(RemapButton.EAST.id to "C")
        val c = open(bridge)
        assertEquals("C", c.remapNames.value[RemapButton.EAST.id])
    }

    @Test fun `back leaves the screen`() {
        val c = open()
        c.handleKeyDown(KEY_EAST)
        assertFalse(c.currentScreen is IGMScreen.ButtonMappings)
    }

    @Test fun `a press that names no button binds nothing`() {
        val bridge = FakeRetroArchBridge()
        val c = open(bridge)
        c.handleKeyDown(KEY_SOUTH)
        c.handleKeyDown(1234)
        assertTrue(screen(c).listening)
        assertTrue(bridge.remapSets.isEmpty())
    }

    @Test fun `a hat D-pad still binds a row when the mapping has no D-pad keycodes`() {
        val bridge = FakeRetroArchBridge()
        val hatMapping = IgmInputMapping(
            buttonKeycodes = mapOf(
                CanonicalButton.BTN_SOUTH to listOf(KEY_SOUTH),
                CanonicalButton.BTN_EAST to listOf(KEY_EAST),
            ),
            menuConfirm = CanonicalButton.BTN_SOUTH,
            menuBack = CanonicalButton.BTN_EAST,
        )
        val c = testController(bridge).apply {
            setInputMapping(hatMapping)
            openMenu()
            openButtonMappings()
        }
        c.handleKeyDown(KEY_DOWN)
        c.handleKeyDown(KEY_SOUTH)
        assertTrue(screen(c).listening)
        c.handleKeyDown(KEY_UP)
        assertFalse(screen(c).listening)
        assertEquals(RemapButton.UP.id, bridge.remap[RemapButton.DOWN.id])
    }

    // The regression this screen shares with Shortcuts: entering pushes a level on the provider
    // navigator, so Back has to step that navigator back too, not just pop the IGM screen. Left
    // undone, the Input category screen underneath is left one level too deep and the first Back
    // press on it looks dead.
    @Test fun `back returns the settings tree to the input category, not one level too deep`() {
        val c = testController(InputCategoryBridge())
        c.openMenu()
        val settingsIndex = c.buildMenuOptions().settingsIndex
        repeat(settingsIndex) { c.handleKeyDown(KEY_DOWN) }
        c.handleKeyDown(KEY_SOUTH) // open Settings
        c.handleKeyDown(KEY_SOUTH) // descend into Input
        c.handleKeyDown(KEY_SOUTH) // descend into Button Mappings; the controller intercepts this
        assertTrue(c.currentScreen is IGMScreen.ButtonMappings)

        c.handleKeyDown(KEY_EAST) // back out of Button Mappings

        val afterFirstBack = c.currentScreen as IGMScreen.ProviderSettings
        assertEquals(listOf(CuratedCatalog.CATEGORY_INPUT), afterFirstBack.path)

        // A stale provider level left behind by the bug answers this second Back with the same
        // screen again instead of climbing to the root.
        c.handleKeyDown(KEY_EAST)
        assertEquals(emptyList<String>(), (c.currentScreen as IGMScreen.ProviderSettings).path)
    }
}
