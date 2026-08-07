package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class IntProvider : IgmSettingsProvider {
    override fun screen(path: List<String>): GenericIgmSettingsScreen = when (path) {
        emptyList<String>() -> GenericIgmSettingsScreen(
            "Settings",
            listOf(
                GenericIgmSettingsItem.Category("video", "Video"),
                GenericIgmSettingsItem.Choice("smooth", "Smoothing", "Off"),
            ),
        )
        listOf("video") -> GenericIgmSettingsScreen("Video", listOf(GenericIgmSettingsItem.Choice("scale", "Scale", "1x")))
        else -> GenericIgmSettingsScreen("", emptyList())
    }
    override fun cycle(itemKey: String, direction: Int) {}
    override fun activate(itemKey: String): IgmSettingsExit.Prompt? = null
    override fun exitPrompt(): IgmSettingsExit = IgmSettingsExit.Close
    override fun setOnChanged(callback: () -> Unit) {}
}

private class ProviderBridge : FakeEmulatorBridge() {
    override fun settingsProvider(): IgmSettingsProvider = IntProvider()
}

class IGMControllerProviderSettingsTest {

    private fun openSettings(): IGMController {
        val c = testController(ProviderBridge())
        c.openMenu()
        val menu = c.buildMenuOptions()
        val settingsIndex = menu.settingsIndex
        repeat(settingsIndex) { c.handleKeyDown(20) }
        c.handleKeyDown(96)
        return c
    }

    @Test
    fun `settings opens the provider root`() {
        val c = openSettings()
        val screen = c.currentScreen as IGMScreen.ProviderSettings
        assertEquals(emptyList<String>(), screen.path)
        assertEquals(listOf("Video", "Smoothing"), c.settingsItems.value.map { it.label })
    }

    @Test
    fun `descending a category updates the rendered screen`() {
        val c = openSettings()
        c.handleKeyDown(96)
        val screen = c.currentScreen as IGMScreen.ProviderSettings
        assertEquals(listOf("video"), screen.path)
        assertEquals("Video", screen.title)
        assertEquals(listOf("Scale"), c.settingsItems.value.map { it.label })
    }

    @Test
    fun `back at the root returns to the menu`() {
        val c = openSettings()
        c.handleKeyDown(97)
        assertTrue(c.currentScreen is IGMScreen.Menu)
    }
}
