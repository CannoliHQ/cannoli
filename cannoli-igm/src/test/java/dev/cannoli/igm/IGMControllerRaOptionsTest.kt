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
}

// latency is at index 2 in RaOptionCatalog.categories
private const val LATENCY_INDEX = 2

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

    @Test fun savePromptAppearsWhenDirtyAndPlatformSavesContentDir() {
        val (c, bridge) = buildController()
        c.enterLatencyCategory()
        c.handleKeyDown(22)

        c.handleKeyDown(97)
        c.handleKeyDown(97)

        c.onClose = {}
        c.handleKeyDown(97)

        assertTrue(c.currentScreen is IGMScreen.SavePrompt)
        assertEquals(0, (c.currentScreen as IGMScreen.SavePrompt).selectedIndex)

        c.handleKeyDown(96)

        assertEquals(listOf(RaOverrideScope.CONTENT_DIR), bridge.savedScopes)
    }

    @Test fun gameScopeSavesGameOverride() {
        val (c, bridge) = buildController()
        c.enterLatencyCategory()
        c.handleKeyDown(22)

        c.handleKeyDown(97)
        c.handleKeyDown(97)

        c.onClose = {}
        c.handleKeyDown(97)

        assertTrue(c.currentScreen is IGMScreen.SavePrompt)

        c.handleKeyDown(20)
        c.handleKeyDown(96)

        assertEquals(listOf(RaOverrideScope.GAME), bridge.savedScopes)
    }

    @Test fun dontSaveClosesWithoutSaving() {
        val (c, bridge) = buildController()
        c.enterLatencyCategory()
        c.handleKeyDown(22)

        c.handleKeyDown(97)
        c.handleKeyDown(97)

        var closed = false
        c.onClose = { closed = true }
        c.handleKeyDown(97)

        assertTrue(c.currentScreen is IGMScreen.SavePrompt)

        c.handleKeyDown(20)
        c.handleKeyDown(20)
        c.handleKeyDown(96)

        assertTrue(bridge.savedScopes.isEmpty())
        assertTrue(closed)
    }

    @Test fun openMenuClearsDirty() {
        val (c, _) = buildController()
        c.enterLatencyCategory()
        c.handleKeyDown(22)

        c.openMenu()

        var closed = false
        c.onClose = { closed = true }
        c.handleKeyDown(97)

        assertTrue(closed)
        assertFalse(c.currentScreen is IGMScreen.SavePrompt)
    }
}
