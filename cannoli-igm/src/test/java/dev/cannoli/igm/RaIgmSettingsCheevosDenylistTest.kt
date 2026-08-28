package dev.cannoli.igm

import dev.cannoli.core.CheevosSessionKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RetroAchievements session keys are injected fresh into the per-launch config every launch, so
 * a saved override must never carry one back onto disk. RaOptionCatalog exposes none of them today,
 * so this pins the defence-in-depth guard: even a session key that somehow reached the changed set
 * is dropped before the override is written.
 */
private class DenylistHost(private val keys: List<String>) : RaSettingsHost {
    val savedKeys = mutableListOf<Set<String>>()

    override fun coreOptions() = keys.map { CoreOptionRef(key = it) }

    override fun raGetSetting(key: String): RaSetting? =
        if (key in keys) RaSetting(key, key, RaSettingType.ENUM, MachineValue("off"), "off", options = listOf(RaOption(MachineValue("off"), "off"), RaOption(MachineValue("on"), "on"))) else null

    override fun raSetSetting(key: String, value: MachineValue) = true
    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) { savedKeys.add(keys) }
    override fun setOnRaSettingApplied(callback: (String, String) -> Unit) {}
}

class RaIgmSettingsCheevosDenylistTest {

    @Test fun `saving drops every cheevos session key and keeps the rest`() {
        val host = DenylistHost(CheevosSessionKeys.ALL.toList() + "run_ahead_frames")
        val p = RaIgmSettingsProvider(host = host)
        p.screen(listOf("emulator"))
        for (k in CheevosSessionKeys.ALL) p.cycle(k, 1)
        p.cycle("run_ahead_frames", 1)

        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(1)

        assertEquals(listOf(setOf("run_ahead_frames")), host.savedKeys)
    }

    @Test fun `a save of only cheevos keys writes an empty set`() {
        val host = DenylistHost(CheevosSessionKeys.ALL.toList())
        val p = RaIgmSettingsProvider(host = host)
        p.screen(listOf("emulator"))
        for (k in CheevosSessionKeys.ALL) p.cycle(k, 1)

        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(0)

        assertTrue(host.savedKeys.single().isEmpty())
    }
}
