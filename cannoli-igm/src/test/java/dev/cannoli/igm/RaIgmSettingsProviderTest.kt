package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRaHost : RaSettingsHost {
    val settings = mutableMapOf<String, RaSetting>()
    val setCalls = mutableListOf<Pair<String, String>>()
    val savedScopes = mutableListOf<RaOverrideScope>()
    val savedKeys = mutableListOf<Set<String>>()
    private var appliedCb: ((String, String) -> Unit)? = null
    val screens = mutableMapOf<String, List<RaScreenRow>>()

    override fun raGetSetting(key: String): RaSetting? = settings[key]
    override fun raScreenRows(label: String): List<RaScreenRow> = screens[label].orEmpty()
    // Mirrors the native contract: false means the key resolves to nothing, so nothing was queued.
    var setSucceeds = true
    override fun raSetSetting(key: String, value: String): Boolean {
        setCalls.add(key to value)
        return setSucceeds
    }
    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {
        savedScopes.add(scope)
        savedKeys.add(keys)
    }
    override fun setOnRaSettingApplied(callback: (String, String) -> Unit) { appliedCb = callback }
    fun fireApplied(key: String, value: String) { appliedCb?.invoke(key, value) }
}

private fun host(): FakeRaHost = FakeRaHost().apply {
    screens[""] = listOf(
        RaScreenRow("latency_settings", "Latency", isMenu = true),
        RaScreenRow("midi_settings", "MIDI", isMenu = true),
    )
    screens["latency_settings"] = listOf(
        RaScreenRow("run_ahead_frames", "Run-Ahead Frames", isMenu = false),
        RaScreenRow("run_ahead_hide_warnings", "Hide Run-Ahead Warnings", isMenu = false),
    )
    settings["run_ahead_frames"] =
        RaSetting("run_ahead_frames", "Run-Ahead Frames", RaSettingType.INT, "1", min = 0f, max = 4f, step = 1f)
    // A boolean that is still menu-registered. run_ahead_enabled was the fixture until RetroArch
    // stopped registering it in favour of the runahead_mode enum.
    settings["run_ahead_hide_warnings"] =
        RaSetting("run_ahead_hide_warnings", "Hide Run-Ahead Warnings", RaSettingType.BOOL, "false")
}

private fun provider(
    h: FakeRaHost,
): RaIgmSettingsProvider =
    RaIgmSettingsProvider(host = h)

private val LATENCY = "latency_settings"

class RaIgmSettingsProviderTest {

    @Test
    fun `root mirrors RetroArch's own settings list, minus what Cannoli refuses`() {
        val p = provider(host())
        val items = p.screen(emptyList())
        val labels = items.items.map { it.label }
        // midi_settings is refused here because RetroArch has no settings_show_ flag for it;
        // the screens that do are turned off in the launch config and never reach this list.
        assertEquals(listOf("Latency"), labels)
        assertTrue(items.items.all { it is GenericIgmSettingsItem.Category })
    }

    @Test
    fun `a category screen lists its settings as rows`() {
        val p = provider(host())
        val rows = p.screen(listOf(LATENCY)).items
        val bool = rows.filterIsInstance<GenericIgmSettingsItem.Choice>().first { it.key == "run_ahead_hide_warnings" }
        assertEquals("Hide Run-Ahead Warnings", bool.label)
        assertEquals(RaOptionStrings().off, bool.value)
    }

    @Test
    fun `cycling an RA setting calls the host and flips the displayed value`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)
        assertEquals(listOf("run_ahead_hide_warnings" to "true"), h.setCalls)
        assertEquals(RaOptionStrings().on,
            p.screen(listOf(LATENCY)).items.filterIsInstance<GenericIgmSettingsItem.Choice>()
                .first { it.key == "run_ahead_hide_warnings" }.value)
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

    // A write the native side could not queue must not be recorded as a change, or exiting prompts
    // to save something that never happened and the override comes out without it.
    @Test
    fun `a write that never queued leaves the menu clean`() {
        val h = host()
        h.setSucceeds = false
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)

        assertEquals(listOf("run_ahead_hide_warnings" to "true"), h.setCalls)
        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
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
        p.cycle("run_ahead_hide_warnings", 1)
        val prompt = p.exitPrompt() as IgmSettingsExit.Prompt
        assertEquals(
            listOf(RaOptionStrings().savePlatform, RaOptionStrings().saveGame, RaOptionStrings().dontSave),
            prompt.options,
        )
        prompt.onChoice(0)
        assertEquals(listOf(RaOverrideScope.SYSTEM), h.savedScopes)
    }

    @Test
    fun `exit after saving the game scope routes GAME`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(1)
        assertEquals(listOf(RaOverrideScope.GAME), h.savedScopes)
    }

    @Test
    fun `discarding on exit saves nothing and clears the dirty flag`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(2)
        assertTrue(h.savedScopes.isEmpty())
        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
    }

    @Test
    fun `saving writes exactly the changed RA keys and clears after`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)
        p.cycle("run_ahead_frames", 1)
        p.screen(emptyList())
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(1)
        assertEquals(listOf(setOf("run_ahead_hide_warnings", "run_ahead_frames")), h.savedKeys)

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
        p.cycle("run_ahead_hide_warnings", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(2)
        p.cycle("run_ahead_frames", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(1)
        assertEquals(listOf(setOf("run_ahead_frames")), h.savedKeys)
    }

    // video_threaded lives under Video > Output, so this also covers a subcategory row carrying the
    // hint rather than only a top-level one.
    @Test
    fun `a restart-required setting carries the restart hint`() {
        val h = host()
        h.settings["video_threaded"] =
            RaSetting("video_threaded", "Threaded Video", RaSettingType.BOOL, "false", requiresRestart = true)
        val p = provider(h)
        h.screens["video_output_settings"] =
            listOf(RaScreenRow("video_threaded", "Threaded Video", isMenu = false))
        val row = p.screen(listOf("video_output_settings")).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>().firstOrNull { it.key == "video_threaded" }
        assertEquals(RaOptionStrings().restartHint, row?.hint)
    }
}
