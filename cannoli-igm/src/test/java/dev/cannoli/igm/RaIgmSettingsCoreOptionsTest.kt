package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PREFIX = "core::"

private class CoreOptionHost(private val keys: List<String>) : RaSettingsHost {
    val written = mutableListOf<Pair<String, String>>()

    override fun coreOptions() = keys.map { CoreOptionRef(key = "$PREFIX$it") }

    override fun raGetSetting(key: String): RaSetting? {
        if (!key.startsWith(PREFIX)) return null
        return RaSetting(
            key = key,
            label = key.removePrefix(PREFIX).replace('_', ' '),
            type = RaSettingType.ENUM,
            value = "disabled",
            options = listOf("disabled", "enabled"),
        )
    }

    override fun raSetSetting(key: String, value: String): Boolean {
        written += key to value
        return true
    }

    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {}
    override fun setOnRaSettingApplied(callback: (String, String) -> Unit) {}
    override fun getLocalToggle(key: String, default: Boolean) = default
    override fun setLocalToggle(key: String, value: Boolean) {}
}

class RaIgmSettingsCoreOptionsTest {

    private fun provider(keys: List<String>) =
        CoreOptionHost(keys).let { it to RaIgmSettingsProvider(host = it) }

    @Test fun `a core with options gets an emulator row`() {
        val (_, p) = provider(listOf("gambatte_gb_colorization"))
        val root = p.screen(emptyList())
        assertTrue(root.items.any { it is GenericIgmSettingsItem.Category && it.key == "emulator" })
    }

    // Absent rather than empty, so a core with nothing to configure has no dead end.
    @Test fun `a core with no options gets no emulator row`() {
        val (_, p) = provider(emptyList())
        val root = p.screen(emptyList())
        assertNull(root.items.firstOrNull { it is GenericIgmSettingsItem.Category && it.key == "emulator" })
    }

    @Test fun `the emulator screen lists the core's options`() {
        val (_, p) = provider(listOf("gambatte_gb_colorization", "gambatte_gb_internal_palette"))
        val screen = p.screen(listOf("emulator"))
        assertEquals(2, screen.items.size)
        assertEquals("${PREFIX}gambatte_gb_colorization", (screen.items[0] as GenericIgmSettingsItem.Choice).key)
    }

    @Test fun `cycling an option writes the prefixed key back`() {
        val (host, p) = provider(listOf("gambatte_gb_colorization"))
        p.screen(listOf("emulator"))

        p.cycle("${PREFIX}gambatte_gb_colorization", 1)

        assertEquals(1, host.written.size)
        assertEquals("${PREFIX}gambatte_gb_colorization", host.written[0].first)
        assertEquals("enabled", host.written[0].second)
    }
}

private class CategorisedHost(private val refs: List<CoreOptionRef>) : RaSettingsHost {
    override fun coreOptions() = refs
    override fun raGetSetting(key: String) = RaSetting(
        key = key,
        label = "${key.removePrefix(PREFIX)} (Restart)",
        type = RaSettingType.ENUM,
        value = "a",
        options = listOf("a", "b"),
    )
    override fun raSetSetting(key: String, value: String) = true
    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {}
    override fun setOnRaSettingApplied(callback: (String, String) -> Unit) {}
    override fun getLocalToggle(key: String, default: Boolean) = default
    override fun setLocalToggle(key: String, value: Boolean) {}
}

class RaIgmSettingsCoreCategoriesTest {

    private fun provider(refs: List<CoreOptionRef>) =
        RaIgmSettingsProvider(host = CategorisedHost(refs))

    @Test fun `a core with categories shows them instead of a flat list`() {
        val p = provider(listOf(
            CoreOptionRef("${PREFIX}a", "video", "Video"),
            CoreOptionRef("${PREFIX}b", "audio", "Audio"),
        ))
        val screen = p.screen(listOf("emulator"))
        assertEquals(2, screen.items.size)
        assertEquals("Video", (screen.items[0] as GenericIgmSettingsItem.Category).label)
    }

    @Test fun `drilling into a category lists only its options`() {
        val p = provider(listOf(
            CoreOptionRef("${PREFIX}a", "video", "Video"),
            CoreOptionRef("${PREFIX}b", "audio", "Audio"),
            CoreOptionRef("${PREFIX}c", "video", "Video"),
        ))
        val screen = p.screen(listOf("emulator", "video"))
        assertEquals(2, screen.items.size)
        assertEquals("Video", screen.title)
    }

    // One menu leading to a single menu is worse than no menu, so a flat core skips the level.
    @Test fun `a core with no categories goes straight to its options`() {
        val p = provider(listOf(CoreOptionRef("${PREFIX}a"), CoreOptionRef("${PREFIX}b")))
        val screen = p.screen(listOf("emulator"))
        assertEquals(2, screen.items.size)
        assertTrue(screen.items.all { it is GenericIgmSettingsItem.Choice })
    }

    @Test fun `the restart suffix comes off the label and becomes the hint`() {
        val p = provider(listOf(CoreOptionRef("${PREFIX}gb_model")))
        val row = p.screen(listOf("emulator")).items[0] as GenericIgmSettingsItem.Choice
        assertEquals("gb_model", row.label)
        assertEquals("Applies On Relaunch", row.hint)
    }
}
