package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SCREEN = "a_screen"

private class DiscardHost : RaSettingsHost {
    val settings = mutableMapOf<String, RaSetting>()
    val setCalls = mutableListOf<Pair<String, String>>()
    val savedKeys = mutableListOf<Set<String>>()

    val screens = mutableMapOf<String, List<RaScreenRow>>()

    override fun raGetSetting(key: String): RaSetting? = settings[key]
    override fun raScreenRows(label: String): List<RaScreenRow> = screens[label].orEmpty()
    override fun raSetSetting(key: String, value: MachineValue): Boolean {
        setCalls.add(key to value.raw)
        settings[key] = settings[key]?.copy(machineValue = value, displayValue = value.raw) ?: return false
        return true
    }
    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) { savedKeys.add(keys) }
    val cannoliSaves = mutableListOf<Pair<RaOverrideScope, Set<String>>>()
    override fun saveCannoliOverride(scope: RaOverrideScope, changed: Set<String>) {
        cannoliSaves.add(scope to changed)
    }
    override fun setOnRaSettingApplied(callback: (String, String) -> Unit) {}

    fun put(key: String, value: String, options: List<RaOption>) {
        screens[SCREEN] = screens[SCREEN].orEmpty() + RaScreenRow(key, key, isMenu = false)
        screens[""] = listOf(RaScreenRow(SCREEN, "Screen", isMenu = true))
        settings[key] = RaSetting(
            key = key,
            label = key,
            type = RaSettingType.ENUM,
            machineValue = MachineValue(value),
            displayValue = value,
            options = options,
        )
    }
}

/**
 * Discard used to drop only the pending write, leaving every edit live until the next launch
 * recomposed the config from tiers it had never reached. These pin that it now puts the running
 * game back as it was.
 */
class RaIgmSettingsDiscardTest {

    private fun provider(host: DiscardHost) =
        RaIgmSettingsProvider(host = host, strings = RaOptionStrings(), curated = false)

    private fun discard(p: IgmSettingsProvider) {
        val exit = p.exitPrompt()
        assertTrue(exit is IgmSettingsExit.Prompt)
        (exit as IgmSettingsExit.Prompt).choose(SaveAnswer.discard)
    }

    @Test fun `discard puts a changed setting back`() {
        val host = DiscardHost().apply { put("rewind_enable", "false", listOf(RaOption(MachineValue("false"), "false".uppercase()), RaOption(MachineValue("true"), "true".uppercase()))) }
        val p = provider(host)
        p.screen(listOf(SCREEN))
        p.cycle("rewind_enable", 1)
        assertEquals("true", host.settings["rewind_enable"]?.machineValue?.raw)

        discard(p)

        assertEquals("false", host.settings["rewind_enable"]?.machineValue?.raw)
    }

    // First capture wins, so several moves still return to the value held on the way in.
    @Test fun `discard restores the value from before the first edit`() {
        val host = DiscardHost().apply { put("k", "a", listOf(RaOption(MachineValue("a"), "a".uppercase()), RaOption(MachineValue("b"), "b".uppercase()), RaOption(MachineValue("c"), "c".uppercase()))) }
        val p = provider(host)
        p.screen(listOf(SCREEN))
        p.cycle("k", 1)
        p.cycle("k", 1)
        assertEquals("c", host.settings["k"]?.machineValue?.raw)

        discard(p)

        assertEquals("a", host.settings["k"]?.machineValue?.raw)
    }

    @Test fun `saving does not restore`() {
        val host = DiscardHost().apply { put("k", "a", listOf(RaOption(MachineValue("a"), "a".uppercase()), RaOption(MachineValue("b"), "b".uppercase()))) }
        val p = provider(host)
        p.screen(listOf(SCREEN))
        p.cycle("k", 1)

        val exit = p.exitPrompt() as IgmSettingsExit.Prompt
        exit.choose(SaveAnswer.platform)

        assertEquals("b", host.settings["k"]?.machineValue?.raw)
        assertEquals(setOf("k"), host.savedKeys.single())
    }

    // A change applied outside the tree stages before it writes, so the snapshot is the old value.
    @Test fun `discard restores a change staged from outside the tree`() {
        val host = DiscardHost().apply { put("input_overlay_enable", "false", listOf(RaOption(MachineValue("false"), "false".uppercase()), RaOption(MachineValue("true"), "true".uppercase()))) }
        val p = provider(host)
        p.screen(listOf(SCREEN))

        p.markChangedExternally(setOf("input_overlay_enable"))
        host.raSetSetting("input_overlay_enable", MachineValue("true"))

        discard(p)

        assertEquals("false", host.settings["input_overlay_enable"]?.machineValue?.raw)
    }

    // A host writes its own value only when this visit touched it. Without the changed set it would
    // copy a game-scoped overlay onto the whole platform whenever any unrelated setting was saved
    // for the platform, which is a choice the user never made.
    @Test fun `a cannoli value is offered only the keys this visit changed`() {
        val host = DiscardHost().apply { put("k", "a", listOf(RaOption(MachineValue("a"), "a".uppercase()), RaOption(MachineValue("b"), "b".uppercase()))) }
        val p = provider(host)
        p.screen(listOf(SCREEN))
        p.cycle("k", 1)

        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.platform)

        val (scope, changed) = host.cannoliSaves.single()
        assertEquals(RaOverrideScope.SYSTEM, scope)
        assertEquals(setOf("k"), changed)
        assertFalse(changed.contains("cannoli_overlay"))
    }

    @Test fun `nothing changed means no prompt and nothing written`() {
        val host = DiscardHost().apply { put("k", "a", listOf(RaOption(MachineValue("a"), "a".uppercase()), RaOption(MachineValue("b"), "b".uppercase()))) }
        val p = provider(host)
        p.screen(listOf(SCREEN))

        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
        assertTrue(host.setCalls.isEmpty())
    }

    @Test fun `a second visit starts with nothing to restore`() {
        val host = DiscardHost().apply { put("k", "a", listOf(RaOption(MachineValue("a"), "a".uppercase()), RaOption(MachineValue("b"), "b".uppercase()))) }
        val p = provider(host)
        p.screen(listOf(SCREEN))
        p.cycle("k", 1)
        discard(p)
        host.setCalls.clear()

        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
        assertTrue(host.setCalls.isEmpty())
        assertFalse(host.settings["k"]?.machineValue == MachineValue("b"))
    }
}
