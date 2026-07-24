package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProvider : IgmSettingsProvider {
    val cycles = mutableListOf<Pair<String, Int>>()
    val activations = mutableListOf<String>()
    var exit: IgmSettingsExit = IgmSettingsExit.Close
    var videoValue = "Off"
    private var onChanged: (() -> Unit)? = null

    override fun screen(path: List<String>): GenericIgmSettingsScreen = when (path) {
        emptyList<String>() -> GenericIgmSettingsScreen(
            "Settings",
            listOf(
                GenericIgmSettingsItem.Category("video", "Video"),
                GenericIgmSettingsItem.Action("info", "Info"),
            ),
        )
        listOf("video") -> GenericIgmSettingsScreen(
            "Video",
            listOf(GenericIgmSettingsItem.Choice("smooth", "Smoothing", videoValue)),
        )
        else -> GenericIgmSettingsScreen("", emptyList())
    }

    override fun cycle(itemKey: String, direction: Int) {
        cycles.add(itemKey to direction)
        videoValue = "On"
    }

    override fun activate(itemKey: String) { activations.add(itemKey) }
    override fun exitPrompt(): IgmSettingsExit = exit
    override fun setOnChanged(callback: () -> Unit) { onChanged = callback }
    fun fireChanged() { onChanged?.invoke() }
}

private class ProviderBridge(private val provider: IgmSettingsProvider) : FakeEmulatorBridge() {
    override fun settingsProvider(): IgmSettingsProvider = provider
}

private fun build(): Triple<IGMController, FakeProvider, ProviderBridge> {
    val p = FakeProvider()
    val bridge = ProviderBridge(p)
    val c = IGMController(bridge, "Game")
    c.openMenu()
    c.openProviderSettings()
    return Triple(c, p, bridge)
}

class IGMControllerProviderSettingsTest {

    @Test
    fun `root shows provider items`() {
        val (c, _, _) = build()
        val screen = c.currentScreen as IGMScreen.ProviderSettings
        assertEquals(emptyList<String>(), screen.path)
        assertEquals("Settings", screen.title)
        assertEquals(listOf("Video", "Info"), c.settingsItems.value.map { it.label })
    }

    @Test
    fun `confirm on category descends and sets path`() {
        val (c, _, _) = build()
        c.handleKeyDown(96)
        val screen = c.currentScreen as IGMScreen.ProviderSettings
        assertEquals(listOf("video"), screen.path)
        assertEquals("Video", screen.title)
        assertEquals(listOf("Smoothing"), c.settingsItems.value.map { it.label })
    }

    @Test
    fun `back from a category restores the parent cursor`() {
        val (c, _, _) = build()
        c.handleKeyDown(20)
        c.handleKeyDown(19)
        c.handleKeyDown(96)
        c.handleKeyDown(97)
        val screen = c.currentScreen as IGMScreen.ProviderSettings
        assertEquals(emptyList<String>(), screen.path)
        assertEquals(0, screen.selectedIndex)
        assertEquals(listOf("Video", "Info"), c.settingsItems.value.map { it.label })
    }

    @Test
    fun `left and right cycle the selected choice and re-read`() {
        val (c, p, _) = build()
        c.handleKeyDown(96)
        c.handleKeyDown(22)
        assertEquals(listOf("smooth" to 1), p.cycles)
        assertEquals(listOf("On"), c.settingsItems.value.map { it.value })
        c.handleKeyDown(21)
        assertEquals("smooth" to -1, p.cycles.last())
    }

    @Test
    fun `confirm on action dispatches to the provider`() {
        val (c, p, _) = build()
        c.handleKeyDown(20)
        c.handleKeyDown(96)
        assertEquals(listOf("info"), p.activations)
        assertTrue(c.currentScreen is IGMScreen.ProviderSettings)
    }

    @Test
    fun `back at root with Close exits the subtree`() {
        val (c, _, _) = build()
        c.handleKeyDown(97)
        assertTrue(c.currentScreen is IGMScreen.Menu)
    }

    @Test
    fun `back at root with Prompt pushes the exit prompt`() {
        val (c, p, _) = build()
        var chosen = -1
        p.exit = IgmSettingsExit.Prompt("Save changes", listOf("A", "B", "C")) { chosen = it }
        c.handleKeyDown(97)
        assertTrue(c.currentScreen is IGMScreen.SettingsExitPrompt)
        assertEquals(listOf("A", "B", "C"), c.settingsItems.value.map { it.label })
        c.handleKeyDown(20)
        c.handleKeyDown(96)
        assertEquals(1, chosen)
        assertTrue(c.currentScreen is IGMScreen.Menu)
    }

    @Test
    fun `onChanged refreshes the current level`() {
        val (c, p, _) = build()
        c.handleKeyDown(96)
        p.videoValue = "On"
        p.fireChanged()
        assertEquals(listOf("On"), c.settingsItems.value.map { it.value })
    }
}
