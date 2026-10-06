package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class ResetHost : RaSettingsHost {
    val stored = mutableSetOf<RaOverrideScope>()
    val resets = mutableListOf<RaOverrideScope>()
    val settings = mutableMapOf<String, RaSetting>()
    val savedKeys = mutableListOf<Set<String>>()

    override fun raGetSetting(key: String): RaSetting? = settings[key]
    fun applyNow(key: String, value: MachineValue, watch: Collection<String>): RaApplyResult {
        settings[key] = (settings[key] ?: RaSetting(key, key, RaSettingType.STRING_RO, value, value.raw))
            .copy(machineValue = value, displayValue = value.raw)
        return RaApplyResult(value)
    }

    override fun raApply(
        key: String,
        value: MachineValue,
        watch: Collection<String>,
        onDone: (RaApplyResult?) -> Unit,
    ): Boolean = answerNow(onDone) { applyNow(key, value, watch) }

    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) { savedKeys.add(keys) }

    override fun hasOverrides(scope: RaOverrideScope): Boolean = scope in stored
    override fun resetOverrides(scope: RaOverrideScope) {
        resets.add(scope)
        stored.remove(scope)
    }

    fun seedVideo() {
        val row = CuratedCatalog.categories.first { it.key == CuratedCatalog.CATEGORY_VIDEO }.rows.first()
        for ((k, v) in row.presets[0].values) {
            settings[k] = RaSetting(k, k, RaSettingType.STRING_RO, MachineValue(v), v)
        }
    }
}

private val STRINGS = RaOptionStrings()

class RaIgmSettingsResetTest {

    private fun provider(h: ResetHost) = RaIgmSettingsProvider(host = h, strings = STRINGS, curated = true)

    @Test fun `reset is not offered while nothing is stored`() {
        val h = ResetHost().apply { seedVideo() }
        assertFalse(provider(h).canReset(emptyList()))
    }

    @Test fun `reset is offered once a scope holds something`() {
        val h = ResetHost().apply { seedVideo(); stored.add(RaOverrideScope.GAME) }
        assertTrue(provider(h).canReset(emptyList()))
    }

    @Test fun `reset is not offered below the root`() {
        val h = ResetHost().apply { seedVideo(); stored.add(RaOverrideScope.SYSTEM) }
        assertFalse(provider(h).canReset(listOf(CuratedCatalog.CATEGORY_VIDEO)))
        assertEquals(null, provider(h).resetPrompt(listOf(CuratedCatalog.CATEGORY_VIDEO)))
    }

    @Test fun `the prompt carries its own title`() {
        val h = ResetHost().apply { seedVideo(); stored.add(RaOverrideScope.GAME) }
        assertEquals(STRINGS.resetTitle, provider(h).resetPrompt(emptyList())!!.title)
    }

    @Test fun `the prompt offers both scopes when both hold something`() {
        val h = ResetHost().apply {
            seedVideo()
            stored.addAll(listOf(RaOverrideScope.GAME, RaOverrideScope.SYSTEM))
        }
        val prompt = provider(h).resetPrompt(emptyList())!!
        assertEquals(listOf(STRINGS.resetGame, STRINGS.resetPlatform), prompt.options.map { it.label })
    }

    @Test fun `the prompt leaves out a scope with nothing stored`() {
        val h = ResetHost().apply { seedVideo(); stored.add(RaOverrideScope.SYSTEM) }
        val prompt = provider(h).resetPrompt(emptyList())!!
        assertEquals(listOf(STRINGS.resetPlatform), prompt.options.map { it.label })
    }

    @Test fun `this game is offered before the platform`() {
        val h = ResetHost().apply {
            seedVideo()
            stored.addAll(listOf(RaOverrideScope.SYSTEM, RaOverrideScope.GAME))
        }
        val prompt = provider(h).resetPrompt(emptyList())!!
        assertEquals(STRINGS.resetGame, prompt.options.first().label)
    }

    @Test fun `choosing an option resets that scope and no other`() {
        val h = ResetHost().apply {
            seedVideo()
            stored.addAll(listOf(RaOverrideScope.GAME, RaOverrideScope.SYSTEM))
        }
        val prompt = provider(h).resetPrompt(emptyList())!!
        prompt.options.first { it.label == STRINGS.resetPlatform }.choose()
        assertEquals(listOf(RaOverrideScope.SYSTEM), h.resets)
    }

    @Test fun `a reset leaves nothing for the exit prompt to save`() {
        val h = ResetHost().apply { seedVideo(); stored.add(RaOverrideScope.GAME) }
        val p = provider(h)
        val row = CuratedCatalog.categories.first { it.key == CuratedCatalog.CATEGORY_VIDEO }.rows.first()
        p.screen(listOf(CuratedCatalog.CATEGORY_VIDEO))
        p.cycle(row.key, 1)
        assertTrue("the edit must have made it dirty first", p.exitPrompt() is IgmSettingsExit.Prompt)

        p.resetPrompt(emptyList())!!.options.first().choose()

        assertEquals(IgmSettingsExit.Close, p.exitPrompt())
        assertTrue("and nothing was written", h.savedKeys.isEmpty())
    }
}
