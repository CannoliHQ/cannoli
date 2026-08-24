package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProvider : IgmSettingsProvider {
    val cycles = mutableListOf<Pair<String, Int>>()
    val activations = mutableListOf<String>()
    var exit: IgmSettingsExit = IgmSettingsExit.Close
    var videoValue = "Off"
    var actionPrompt: IgmSettingsExit.Prompt? = null
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

    override fun activate(itemKey: String): IgmSettingsExit.Prompt? {
        activations.add(itemKey)
        return actionPrompt
    }
    override fun exitPrompt(): IgmSettingsExit = exit
    override fun setOnChanged(callback: () -> Unit) { onChanged = callback }
    fun fireChanged() { onChanged?.invoke() }
}

/**
 * A row revealed *above* the one being edited. RetroArch lists its conditional rows after the
 * setting that controls them, so its own screens do not do this today, but the cursor is an index
 * and nothing guarantees that stays true: the row set is RetroArch's and changes when it changes.
 */
private class RevealingProvider : IgmSettingsProvider {
    var revealed = false
    override fun screen(path: List<String>): GenericIgmSettingsScreen = GenericIgmSettingsScreen(
        "Scaling",
        buildList {
            if (revealed) add(GenericIgmSettingsItem.Choice("video_aspect_ratio", "Config Aspect Ratio", "1.33"))
            add(GenericIgmSettingsItem.Choice("aspect_ratio_index", "Aspect Ratio", "Custom"))
            add(GenericIgmSettingsItem.Choice("video_scale_integer", "Integer Scale", "Off"))
        },
    )
    override fun cycle(itemKey: String, direction: Int) { if (itemKey == "aspect_ratio_index") revealed = true }
    override fun activate(itemKey: String): IgmSettingsExit.Prompt? = null
    override fun exitPrompt(): IgmSettingsExit = IgmSettingsExit.Close
    override fun setOnChanged(callback: () -> Unit) {}
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
    override fun activate(itemKey: String): IgmSettingsExit.Prompt? = null
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
    override fun activate(itemKey: String): IgmSettingsExit.Prompt? = null
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

    @Test
    fun `an action that returns a prompt enters the prompt state`() {
        val p = FakeProvider()
        var chosen = -1
        p.actionPrompt = IgmSettingsExit.Prompt("Save?", listOf("A", "B")) { chosen = it }
        val c = ProviderSettingsController(p)
        c.enter()
        c.onNav(ProviderSettingsController.Nav.DOWN)
        val s = c.onNav(ProviderSettingsController.Nav.CONFIRM) as ProviderSettingsController.State.Prompt
        assertEquals(listOf("A", "B"), s.options)
        c.onNav(ProviderSettingsController.Nav.DOWN)
        assertTrue(c.onNav(ProviderSettingsController.Nav.CONFIRM) is ProviderSettingsController.State.Closed)
        assertEquals(1, chosen)
    }

    // The reported bug: RetroArch reveals a row above the one being edited, and because the cursor
    // is an index the highlight slid onto the next setting mid-edit, so the following press changed
    // something the user never selected.
    @Test
    fun `the cursor follows the row being edited when a row is revealed above it`() {
        val c = ProviderSettingsController(RevealingProvider())
        c.enter()
        var state = c.state() as ProviderSettingsController.State.Menu
        assertEquals("aspect_ratio_index", state.items[state.selectedIndex].key)

        // Cycling it reveals a row above it, so the index it occupies changes.
        state = c.onNav(ProviderSettingsController.Nav.RIGHT) as ProviderSettingsController.State.Menu
        assertEquals(
            "the highlight must stay on the row being edited",
            "aspect_ratio_index",
            state.items[state.selectedIndex].key,
        )
    }
}
