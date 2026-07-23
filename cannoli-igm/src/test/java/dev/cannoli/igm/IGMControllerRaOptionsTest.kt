package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class RaFakeBridge : FakeEmulatorBridge() {
    override fun raSettingsSupported() = true
    val settings = mutableMapOf<String, RaSetting>()
    val setCalls = mutableListOf<Pair<String, String>>()
    val savedScopes = mutableListOf<RaOverrideScope>()
    private var appliedCb: ((String, String) -> Unit)? = null
    override fun setOnRaSettingApplied(callback: (String, String) -> Unit) { appliedCb = callback }
    override fun raGetSetting(key: String): RaSetting? = settings[key]
    override fun raSetSetting(key: String, value: String): Boolean {
        setCalls.add(key to value)
        return true
    }
    override fun raSaveOverride(scope: RaOverrideScope) { savedScopes.add(scope) }
    fun fireApplied(key: String, value: String) { appliedCb?.invoke(key, value) }
    val localToggles = mutableMapOf<String, Boolean>()
    override fun getLocalToggle(key: String, default: Boolean): Boolean = localToggles[key] ?: default
    override fun setLocalToggle(key: String, value: Boolean) { localToggles[key] = value }
}

private val LATENCY_INDEX = RaOptionCatalog.categories.indexOfFirst { it.key == "latency" }
private val OSD_INDEX = RaOptionCatalog.categories.indexOfFirst { it.key == "osd" }

private fun buildController(): Pair<IGMController, RaFakeBridge> {
    val bridge = RaFakeBridge()
    bridge.settings["run_ahead_frames"] =
        RaSetting("run_ahead_frames", "Run-Ahead Frames", RaSettingType.INT, "1", min = 0f, max = 4f, step = 1f)
    val c = IGMController(bridge, "Game")
    return c to bridge
}

private fun IGMController.enterLatencyCategory() {
    openMenu()
    push(IGMScreen.RaOptions(selectedIndex = LATENCY_INDEX))
    handleKeyDown(96)
}

class IGMControllerRaOptionsTest {

    @Test fun cyclingRightSetsValueAndMarksDirty() {
        val (c, bridge) = buildController()
        c.enterLatencyCategory()
        assertTrue(c.currentScreen is IGMScreen.RaOptionsCategory)

        c.handleKeyDown(22)

        assertEquals(1, bridge.setCalls.size)
        assertEquals("run_ahead_frames" to "2", bridge.setCalls[0])
        assertEquals("2", c.settingsItems.value[0].value)
    }

    @Test fun echoCancellationKeepsLatestOptimisticValue() {
        val (c, bridge) = buildController()
        c.enterLatencyCategory()

        c.handleKeyDown(22)
        c.handleKeyDown(22)

        assertEquals("3", c.settingsItems.value[0].value)
        assertEquals(2, bridge.setCalls.size)

        bridge.fireApplied("run_ahead_frames", "2")
        assertEquals("3", c.settingsItems.value[0].value)

        bridge.fireApplied("run_ahead_frames", "3")
        assertEquals("3", c.settingsItems.value[0].value)
    }

    @Test fun applyConfirmReconcilesToAuthoritativeValue() {
        val (c, bridge) = buildController()
        c.enterLatencyCategory()

        c.handleKeyDown(22)

        assertEquals("2", c.settingsItems.value[0].value)

        bridge.fireApplied("run_ahead_frames", "9")

        assertEquals("9", c.settingsItems.value[0].value)
    }

    @Test fun savePromptAppearsOnSettingsExitAndPlatformSavesContentDir() {
        val (c, bridge) = buildController()
        c.enterLatencyCategory()
        c.handleKeyDown(22)

        c.handleKeyDown(97) // category -> RaOptions
        c.handleKeyDown(97) // RaOptions (dirty) -> SavePrompt

        assertTrue(c.currentScreen is IGMScreen.SavePrompt)
        assertEquals(0, (c.currentScreen as IGMScreen.SavePrompt).selectedIndex)

        c.handleKeyDown(96)

        assertEquals(listOf(RaOverrideScope.CONTENT_DIR), bridge.savedScopes)
        assertTrue(c.currentScreen is IGMScreen.Menu)
    }

    @Test fun gameScopeSavesGameOverride() {
        val (c, bridge) = buildController()
        c.enterLatencyCategory()
        c.handleKeyDown(22)

        c.handleKeyDown(97)
        c.handleKeyDown(97)

        assertTrue(c.currentScreen is IGMScreen.SavePrompt)

        c.handleKeyDown(20)
        c.handleKeyDown(96)

        assertEquals(listOf(RaOverrideScope.GAME), bridge.savedScopes)
        assertTrue(c.currentScreen is IGMScreen.Menu)
    }

    @Test fun dontSaveReturnsToMenuWithoutSaving() {
        val (c, bridge) = buildController()
        c.enterLatencyCategory()
        c.handleKeyDown(22)

        c.handleKeyDown(97)
        c.handleKeyDown(97)

        assertTrue(c.currentScreen is IGMScreen.SavePrompt)

        c.handleKeyDown(20)
        c.handleKeyDown(20)
        c.handleKeyDown(96)

        assertTrue(bridge.savedScopes.isEmpty())
        assertTrue(c.currentScreen is IGMScreen.Menu)
    }

    @Test fun backOnSavePromptDiscardsAndReturnsToMenu() {
        val (c, bridge) = buildController()
        c.enterLatencyCategory()
        c.handleKeyDown(22)

        c.handleKeyDown(97) // category -> RaOptions
        c.handleKeyDown(97) // RaOptions (dirty) -> SavePrompt
        assertTrue(c.currentScreen is IGMScreen.SavePrompt)

        c.handleKeyDown(97) // B on SavePrompt = discard + leave

        assertTrue(bridge.savedScopes.isEmpty())
        assertTrue(c.currentScreen is IGMScreen.Menu)
    }

    @Test fun exitingCleanSettingsDoesNotPrompt() {
        val (c, _) = buildController()
        c.enterLatencyCategory()

        c.handleKeyDown(97) // category -> RaOptions (no change made)
        c.handleKeyDown(97) // RaOptions (clean) -> Menu

        assertFalse(c.currentScreen is IGMScreen.SavePrompt)
        assertTrue(c.currentScreen is IGMScreen.Menu)
    }

    @Test fun openMenuClearsDirtySoSettingsExitDoesNotPrompt() {
        val (c, _) = buildController()
        c.enterLatencyCategory()
        c.handleKeyDown(22)

        c.openMenu()

        c.push(IGMScreen.RaOptions(selectedIndex = 0))
        c.handleKeyDown(97)

        assertFalse(c.currentScreen is IGMScreen.SavePrompt)
        assertTrue(c.currentScreen is IGMScreen.Menu)
    }

    @Test fun retroArchShortcutOpensNativeMenu() {
        val (c, _) = buildController()
        var opened = false
        c.onOpenNativeMenu = { opened = true }
        c.openMenu()
        c.push(IGMScreen.RaOptions())

        c.handleKeyDown(100)            // RA SETTINGS shortcut

        assertTrue(opened)
    }

    @Test fun retroArchShortcutPromptsWhenDirtyThenOpens() {
        val (c, bridge) = buildController()
        var opened = false
        c.onOpenNativeMenu = { opened = true }
        c.enterLatencyCategory()
        c.handleKeyDown(22)              // dirty
        c.handleKeyDown(97)              // category -> RaOptions

        c.handleKeyDown(100)            // RA SETTINGS while dirty -> SavePrompt
        assertTrue(c.currentScreen is IGMScreen.SavePrompt)
        assertFalse(opened)

        c.handleKeyDown(96)             // confirm Platform save
        assertEquals(listOf(RaOverrideScope.CONTENT_DIR), bridge.savedScopes)
        assertTrue(opened)
    }

    @Test fun cannoliLocalToggleFlipsLocallyWithoutDirtyOverride() {
        val (c, bridge) = buildController()
        c.openMenu()
        c.push(IGMScreen.RaOptions(selectedIndex = OSD_INDEX))
        c.handleKeyDown(96) // enter On-Screen Display category
        assertTrue(c.currentScreen is IGMScreen.RaOptionsCategory)

        // The fake bridge has no RA settings for the osd keys, so only the local
        // toggle (cannoli_osd_reset) resolves.
        assertEquals("Reset OSD", c.settingsItems.value[0].label)
        assertEquals("On", c.settingsItems.value[0].value)

        c.handleKeyDown(22) // cycle the toggle

        assertEquals(false, bridge.localToggles["cannoli_osd_reset"])
        assertEquals("Off", c.settingsItems.value[0].value)
        assertTrue(bridge.setCalls.isEmpty()) // not routed through raSetSetting

        // A host-local pref is not an RA override, so exiting must not prompt to save.
        c.handleKeyDown(97) // category -> RaOptions
        c.handleKeyDown(97) // RaOptions (clean) -> Menu
        assertFalse(c.currentScreen is IGMScreen.SavePrompt)
        assertTrue(bridge.savedScopes.isEmpty())
    }
}
