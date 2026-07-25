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

private class NestedProvider : IgmSettingsProvider {
    override fun screen(path: List<String>): GenericIgmSettingsScreen = when (path) {
        emptyList<String>() -> GenericIgmSettingsScreen(
            "Root",
            listOf(
                GenericIgmSettingsItem.Choice("a", "A", "x"),
                GenericIgmSettingsItem.Category("sub", "Sub"),
            ),
        )
        listOf("sub") -> GenericIgmSettingsScreen("Sub", listOf(GenericIgmSettingsItem.Choice("b", "B", "y")))
        else -> GenericIgmSettingsScreen("", emptyList())
    }
    override fun cycle(itemKey: String, direction: Int) {}
    override fun activate(itemKey: String) {}
    override fun exitPrompt(): IgmSettingsExit = IgmSettingsExit.Close
    override fun setOnChanged(callback: () -> Unit) {}
}

private class ShrinkProvider : IgmSettingsProvider {
    var shrunk = false
    override fun screen(path: List<String>): GenericIgmSettingsScreen =
        if (shrunk) GenericIgmSettingsScreen("Root", listOf(GenericIgmSettingsItem.Choice("a", "A", "x")))
        else GenericIgmSettingsScreen(
            "Root",
            listOf(
                GenericIgmSettingsItem.Choice("a", "A", "x"),
                GenericIgmSettingsItem.Choice("b", "B", "y"),
                GenericIgmSettingsItem.Choice("c", "C", "z"),
            ),
        )
    override fun cycle(itemKey: String, direction: Int) {}
    override fun activate(itemKey: String) {}
    override fun exitPrompt(): IgmSettingsExit = IgmSettingsExit.Close
    override fun setOnChanged(callback: () -> Unit) {}
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
    fun `back restores a non-zero parent cursor`() {
        val c = ProviderSettingsController(NestedProvider())
        c.enter()
        c.onNav(ProviderSettingsController.Nav.DOWN)
        c.onNav(ProviderSettingsController.Nav.CONFIRM)
        val s = c.onNav(ProviderSettingsController.Nav.BACK) as ProviderSettingsController.State.Menu
        assertEquals(emptyList<String>(), s.path)
        assertEquals(1, s.selectedIndex)
    }

    @Test
    fun `cursor clamps when the item list shrinks`() {
        val p = ShrinkProvider()
        val c = ProviderSettingsController(p)
        c.enter()
        c.onNav(ProviderSettingsController.Nav.DOWN)
        c.onNav(ProviderSettingsController.Nav.DOWN)
        assertEquals(2, (c.state() as ProviderSettingsController.State.Menu).selectedIndex)
        p.shrunk = true
        val s = c.state() as ProviderSettingsController.State.Menu
        assertEquals(1, s.items.size)
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
    fun `confirm on an action fires it and yields ActionFired`() {
        val (c, p) = enter()
        c.onNav(ProviderSettingsController.Nav.DOWN)
        val s = c.onNav(ProviderSettingsController.Nav.CONFIRM)
        assertEquals(listOf("info"), p.activations)
        assertTrue(s is ProviderSettingsController.State.ActionFired)
    }

    @Test
    fun `state after an action is the unchanged menu`() {
        val (c, _) = enter()
        c.onNav(ProviderSettingsController.Nav.DOWN)
        c.onNav(ProviderSettingsController.Nav.CONFIRM)
        val s = c.state() as ProviderSettingsController.State.Menu
        assertEquals(1, s.selectedIndex)
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
