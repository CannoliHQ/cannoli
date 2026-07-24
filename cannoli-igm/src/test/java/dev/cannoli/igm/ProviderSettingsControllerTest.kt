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

class ProviderSettingsControllerTest {

    private fun enter(p: FakeProvider = FakeProvider()): Pair<ProviderSettingsController, FakeProvider> {
        val c = ProviderSettingsController(p)
        c.enter()
        return c to p
    }

    @Test
    fun `enter yields the root menu`() {
        val (c, _) = enter()
        val s = c.state() as ProviderSettingsController.State.Menu
        assertEquals(emptyList<String>(), s.path)
        assertEquals("Settings", s.title)
        assertEquals(listOf("Video", "Info"), s.items.map { it.label })
        assertEquals(0, s.selectedIndex)
    }

    @Test
    fun `down moves the cursor and wraps`() {
        val (c, _) = enter()
        assertEquals(1, (c.onNav(ProviderSettingsController.Nav.DOWN) as ProviderSettingsController.State.Menu).selectedIndex)
        assertEquals(0, (c.onNav(ProviderSettingsController.Nav.DOWN) as ProviderSettingsController.State.Menu).selectedIndex)
        assertEquals(1, (c.onNav(ProviderSettingsController.Nav.UP) as ProviderSettingsController.State.Menu).selectedIndex)
    }

    @Test
    fun `confirm on a category descends and sets path`() {
        val (c, _) = enter()
        val s = c.onNav(ProviderSettingsController.Nav.CONFIRM) as ProviderSettingsController.State.Menu
        assertEquals(listOf("video"), s.path)
        assertEquals("Video", s.title)
        assertEquals(listOf("Smoothing"), s.items.map { it.label })
    }

    @Test
    fun `back from a category restores the parent cursor`() {
        val (c, _) = enter()
        c.onNav(ProviderSettingsController.Nav.DOWN)
        c.onNav(ProviderSettingsController.Nav.UP)
        c.onNav(ProviderSettingsController.Nav.CONFIRM)
        val s = c.onNav(ProviderSettingsController.Nav.BACK) as ProviderSettingsController.State.Menu
        assertEquals(emptyList<String>(), s.path)
        assertEquals(0, s.selectedIndex)
    }

    @Test
    fun `left and right cycle the selected choice and re-read`() {
        val (c, p) = enter()
        c.onNav(ProviderSettingsController.Nav.CONFIRM)
        val s = c.onNav(ProviderSettingsController.Nav.RIGHT) as ProviderSettingsController.State.Menu
        assertEquals(listOf("smooth" to 1), p.cycles)
        assertEquals(listOf("On"), s.items.map { (it as GenericIgmSettingsItem.Choice).value })
        c.onNav(ProviderSettingsController.Nav.LEFT)
        assertEquals("smooth" to -1, p.cycles.last())
    }

    @Test
    fun `confirm on an action dispatches and stays put`() {
        val (c, p) = enter()
        c.onNav(ProviderSettingsController.Nav.DOWN)
        val s = c.onNav(ProviderSettingsController.Nav.CONFIRM)
        assertEquals(listOf("info"), p.activations)
        assertTrue(s is ProviderSettingsController.State.Menu)
    }

    @Test
    fun `back at root with Close closes`() {
        val (c, _) = enter()
        assertTrue(c.onNav(ProviderSettingsController.Nav.BACK) is ProviderSettingsController.State.Closed)
    }

    @Test
    fun `back at root with Prompt shows the prompt then closes on choice`() {
        val p = FakeProvider()
        var chosen = -1
        p.exit = IgmSettingsExit.Prompt("Save changes", listOf("A", "B", "C")) { chosen = it }
        val (c, _) = enter(p)
        val prompt = c.onNav(ProviderSettingsController.Nav.BACK) as ProviderSettingsController.State.Prompt
        assertEquals(listOf("A", "B", "C"), prompt.options)
        c.onNav(ProviderSettingsController.Nav.DOWN)
        assertTrue(c.onNav(ProviderSettingsController.Nav.CONFIRM) is ProviderSettingsController.State.Closed)
        assertEquals(1, chosen)
    }

    @Test
    fun `back dismisses the prompt without choosing`() {
        val p = FakeProvider()
        var chosen = -1
        p.exit = IgmSettingsExit.Prompt(null, listOf("A", "B")) { chosen = it }
        val (c, _) = enter(p)
        c.onNav(ProviderSettingsController.Nav.BACK)
        assertTrue(c.onNav(ProviderSettingsController.Nav.BACK) is ProviderSettingsController.State.Closed)
        assertEquals(-1, chosen)
    }

    @Test
    fun `state reflects an async provider change`() {
        val (c, p) = enter()
        c.onNav(ProviderSettingsController.Nav.CONFIRM)
        p.videoValue = "On"
        val s = c.state() as ProviderSettingsController.State.Menu
        assertEquals(listOf("On"), s.items.map { (it as GenericIgmSettingsItem.Choice).value })
    }
}
