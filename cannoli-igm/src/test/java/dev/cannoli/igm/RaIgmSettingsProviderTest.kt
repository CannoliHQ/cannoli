package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RA_MENU_KEY = "__ra_menu__"

private class FakeRaHost : RaSettingsHost {
    val settings = mutableMapOf<String, RaSetting>()
    val setCalls = mutableListOf<Pair<String, String>>()
    val savedScopes = mutableListOf<RaOverrideScope>()
    val savedKeys = mutableListOf<Set<String>>()
    val localToggles = mutableMapOf<String, Boolean>()
    private var appliedCb: ((String, String) -> Unit)? = null

    override fun raGetSetting(key: String): RaSetting? = settings[key]
    override fun raSetSetting(key: String, value: String): Boolean {
        setCalls.add(key to value)
        return true
    }
    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {
        savedScopes.add(scope)
        savedKeys.add(keys)
    }
    override fun setOnRaSettingApplied(callback: (String, String) -> Unit) { appliedCb = callback }
    override fun getLocalToggle(key: String, default: Boolean): Boolean = localToggles[key] ?: default
    override fun setLocalToggle(key: String, value: Boolean) { localToggles[key] = value }
    fun fireApplied(key: String, value: String) { appliedCb?.invoke(key, value) }
}

private fun host(): FakeRaHost = FakeRaHost().apply {
    settings["run_ahead_frames"] =
        RaSetting("run_ahead_frames", "Run-Ahead Frames", RaSettingType.INT, "1", min = 0f, max = 4f, step = 1f)
    settings["run_ahead_enabled"] =
        RaSetting("run_ahead_enabled", "Run-Ahead", RaSettingType.BOOL, "false")
}

private fun provider(h: FakeRaHost, opened: MutableList<Unit> = mutableListOf()): RaIgmSettingsProvider =
    RaIgmSettingsProvider(host = h, onOpenNativeMenu = { opened.add(Unit) })

private val LATENCY = "latency"

class RaIgmSettingsProviderTest {

    @Test
    fun `root lists every catalog category plus a RetroArch Menu action`() {
        val p = provider(host())
        val items = p.screen(emptyList())
        val labels = items.items.map { it.label }
        val catTitles = RaOptionCatalog.categories.map { RaOptionStrings().categoryTitles[it.key] ?: it.key }
        assertEquals(catTitles + RaOptionStrings().nativeMenu, labels)
        assertTrue(items.items.dropLast(1).all { it is GenericIgmSettingsItem.Category })
        assertTrue(items.items.last() is GenericIgmSettingsItem.Action)
    }

    @Test
    fun `a category screen lists its settings as rows`() {
        val p = provider(host())
        val rows = p.screen(listOf(LATENCY)).items
        val bool = rows.filterIsInstance<GenericIgmSettingsItem.Choice>().first { it.key == "run_ahead_enabled" }
        assertEquals("Run-Ahead", bool.label)
        assertEquals(RaOptionStrings().off, bool.value)
    }

    @Test
    fun `cycling an RA setting calls the host and flips the displayed value`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_enabled", 1)
        assertEquals(listOf("run_ahead_enabled" to "true"), h.setCalls)
        assertEquals(RaOptionStrings().on,
            p.screen(listOf(LATENCY)).items.filterIsInstance<GenericIgmSettingsItem.Choice>()
                .first { it.key == "run_ahead_enabled" }.value)
    }

    @Test
    fun `a local toggle writes immediately and is never sent to RA`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf("osd"))
        p.cycle("cannoli_osd_reset", 1)
        assertTrue(h.setCalls.isEmpty())
        assertEquals(false, h.localToggles["cannoli_osd_reset"])
    }

    @Test
    fun `an external apply echo updates the displayed value`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        h.fireApplied("run_ahead_frames", "3")
        assertEquals("3",
            p.screen(listOf(LATENCY)).items.filterIsInstance<GenericIgmSettingsItem.Choice>()
                .first { it.key == "run_ahead_frames" }.value)
    }

    @Test
    fun `our own apply echo is suppressed and does not double-count`() {
        val h = host()
        var changes = 0
        val p = provider(h)
        p.setOnChanged { changes++ }
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_frames", 1)
        val afterCycle = changes
        h.fireApplied("run_ahead_frames", "2")
        assertEquals(afterCycle, changes)
    }

    @Test
    fun `exit is clean when nothing changed`() {
        val p = provider(host())
        p.screen(listOf(LATENCY))
        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
    }

    @Test
    fun `exit after a change prompts and each save scope routes to the host`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_enabled", 1)
        val prompt = p.exitPrompt() as IgmSettingsExit.Prompt
        assertEquals(
            listOf(RaOptionStrings().savePlatform, RaOptionStrings().saveGame, RaOptionStrings().dontSave),
            prompt.options,
        )
        prompt.onChoice(0)
        assertEquals(listOf(RaOverrideScope.CONTENT_DIR), h.savedScopes)
    }

    @Test
    fun `exit after saving the game scope routes GAME`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_enabled", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(1)
        assertEquals(listOf(RaOverrideScope.GAME), h.savedScopes)
    }

    @Test
    fun `discarding on exit saves nothing and clears the dirty flag`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_enabled", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(2)
        assertTrue(h.savedScopes.isEmpty())
        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
    }

    @Test
    fun `saving writes exactly the changed RA keys, excludes local toggles, and clears after`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_enabled", 1)
        p.cycle("run_ahead_frames", 1)
        p.screen(listOf("osd"))
        p.cycle("cannoli_osd_reset", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(1)
        assertEquals(listOf(setOf("run_ahead_enabled", "run_ahead_frames")), h.savedKeys)

        // The set is cleared on save, so a later change saves only itself.
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_frames", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(0)
        assertEquals(setOf("run_ahead_frames"), h.savedKeys.last())
    }

    @Test
    fun `discarding clears the changed set so a later save carries nothing stale`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_enabled", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(2)
        p.cycle("run_ahead_frames", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(1)
        assertEquals(listOf(setOf("run_ahead_frames")), h.savedKeys)
    }

    @Test
    fun `a local toggle change does not make the menu dirty`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf("osd"))
        p.cycle("cannoli_osd_reset", 1)
        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
    }

    @Test
    fun `activating the RetroArch Menu action opens the native menu when clean`() {
        val opened = mutableListOf<Unit>()
        val p = provider(host(), opened)
        p.screen(emptyList())
        assertNull(p.activate(RA_MENU_KEY))
        assertEquals(1, opened.size)
    }

    @Test
    fun `RetroArch Menu when dirty prompts to save first then opens the native menu`() {
        val h = host()
        val opened = mutableListOf<Unit>()
        val p = provider(h, opened)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_enabled", 1)
        val prompt = p.activate(RA_MENU_KEY)!!
        assertEquals(
            listOf(RaOptionStrings().savePlatform, RaOptionStrings().saveGame, RaOptionStrings().dontSave),
            prompt.options,
        )
        assertTrue(opened.isEmpty())
        prompt.onChoice(1)
        assertEquals(listOf(RaOverrideScope.GAME), h.savedScopes)
        assertEquals(1, opened.size)
    }

    @Test
    fun `RetroArch Menu when dirty and discarded still opens the native menu without saving`() {
        val h = host()
        val opened = mutableListOf<Unit>()
        val p = provider(h, opened)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_enabled", 1)
        p.activate(RA_MENU_KEY)!!.onChoice(2)
        assertTrue(h.savedScopes.isEmpty())
        assertEquals(1, opened.size)
    }

    @Test
    fun `RetroArch Menu when dirty and cancelled opens the native menu without saving`() {
        val h = host()
        val opened = mutableListOf<Unit>()
        val p = provider(h, opened)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_enabled", 1)
        val prompt = p.activate(RA_MENU_KEY)!!
        prompt.onCancel!!.invoke()
        assertTrue(h.savedScopes.isEmpty())
        assertEquals(1, opened.size)
    }

    @Test
    fun `a restart-required setting carries the restart hint`() {
        val h = host()
        h.settings["video_threaded"] =
            RaSetting("video_threaded", "Threaded Video", RaSettingType.BOOL, "false", requiresRestart = true)
        val p = provider(h)
        val row = p.screen(listOf("video")).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>().firstOrNull { it.key == "video_threaded" }
        assertEquals(RaOptionStrings().restartHint, row?.hint)
    }
}
