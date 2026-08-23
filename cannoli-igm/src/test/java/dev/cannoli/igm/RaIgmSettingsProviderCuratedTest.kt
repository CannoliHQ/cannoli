package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class CuratedFakeHost : RaSettingsHost {
    val settings = mutableMapOf<String, RaSetting>()
    val setCalls = mutableListOf<Pair<String, String>>()
    val savedKeys = mutableListOf<Set<String>>()
    var coreOptions: List<CoreOptionRef> = emptyList()
    var systemInfo: List<Pair<String, String>> = emptyList()
    // False models RetroArch's real behaviour: the write is queued and the value is not readable
    // back until the run loop applies it.
    var applyWrites: Boolean = true

    override fun coreOptions(): List<CoreOptionRef> = coreOptions
    override fun systemInfo(): List<Pair<String, String>> = systemInfo
    val screens = mutableMapOf<String, List<RaScreenRow>>()
    override fun raScreenRows(label: String): List<RaScreenRow> = screens[label].orEmpty()
    override fun raGetSetting(key: String): RaSetting? = settings[key]
    override fun raSetSetting(key: String, value: String): Boolean {
        setCalls.add(key to value)
        if (applyWrites) {
            settings[key] = (settings[key] ?: RaSetting(key, key, RaSettingType.STRING_RO, value))
                .copy(value = value, rawValue = value)
        }
        return true
    }
    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) { savedKeys.add(keys) }
    private var appliedCb: ((String, String) -> Unit)? = null
    override fun setOnRaSettingApplied(callback: (String, String) -> Unit) { appliedCb = callback }

    /** RetroArch echoes the DISPLAY value, which for a combobox is translated label text. */
    fun echoApplied(key: String, displayValue: String) { appliedCb?.invoke(key, displayValue) }
}

class RaIgmSettingsProviderCuratedTest {

    private fun provider(h: CuratedFakeHost) = RaIgmSettingsProvider(
        host = h,
        strings = RaOptionStrings(),
        curated = true,
    )

    /** Seeds every key of [row] with the values of one of its presets. */
    private fun CuratedFakeHost.seed(row: CuratedCatalog.Row, presetIndex: Int) {
        for ((k, v) in row.presets[presetIndex].values) {
            settings[k] = RaSetting(k, k, RaSettingType.STRING_RO, value = v, rawValue = v)
        }
    }

    private fun row(category: String, key: String) =
        CuratedCatalog.categories.first { it.key == category }.rows.first { it.key == key }

    private fun choices(p: RaIgmSettingsProvider, path: List<String>) =
        p.screen(path).items.filterIsInstance<GenericIgmSettingsItem.Choice>()

    @Test
    fun `a category with no reachable settings is absent rather than empty`() {
        val h = CuratedFakeHost()
        val items = provider(h).screen(emptyList()).items
        assertTrue(items.none { it.key == CuratedCatalog.CATEGORY_VIDEO })
        assertTrue(items.none { it.key == CuratedCatalog.CATEGORY_ADVANCED })
        assertTrue(items.none { it.key == CuratedCatalog.CATEGORY_INFO })
    }

    @Test
    fun `a category appears once its settings resolve`() {
        val h = CuratedFakeHost()
        h.seed(row("video", "curated_screen_scaling"), 0)
        val items = provider(h).screen(emptyList()).items
        assertTrue(items.any { it.key == CuratedCatalog.CATEGORY_VIDEO })
        assertTrue(items.none { it.key == CuratedCatalog.CATEGORY_ADVANCED })
    }

    @Test
    fun `emulator is absent when the core exposes no options`() {
        val h = CuratedFakeHost()
        assertTrue(provider(h).screen(emptyList()).items.none { it.key == CuratedCatalog.CATEGORY_EMULATOR })
    }

    @Test
    fun `emulator appears when the core exposes options`() {
        val h = CuratedFakeHost()
        h.coreOptions = listOf(CoreOptionRef("core_opt_x", "", ""))
        assertTrue(provider(h).screen(emptyList()).items.any { it.key == CuratedCatalog.CATEGORY_EMULATOR })
    }

    @Test
    fun `a row whose keys are unreachable is dropped from its category`() {
        val h = CuratedFakeHost()
        h.seed(row("video", "curated_screen_scaling"), 0)
        val keys = choices(provider(h), listOf("video")).map { it.key }
        assertEquals(listOf("curated_screen_scaling"), keys)
    }

    @Test
    fun `a composite row shows the label of the matching preset`() {
        val h = CuratedFakeHost()
        h.seed(row("video", "curated_screen_sharpness"), 0)
        val r = choices(provider(h), listOf("video")).first { it.key == "curated_screen_sharpness" }
        assertEquals("Sharp", r.value)
    }

    // Curated mode drives RetroArch rather than reporting on it, so a state the menu cannot express
    // is replaced by one it can, instead of being surfaced as Custom.
    @Test
    fun `a row whose live values match no preset adopts the first one`() {
        val h = CuratedFakeHost()
        h.settings["video_smooth"] =
            RaSetting("video_smooth", "video_smooth", RaSettingType.BOOL, value = "?", rawValue = "?")
        val r = choices(provider(h), listOf("video")).first { it.key == "curated_screen_sharpness" }
        assertEquals("Sharp", r.value)
        assertEquals(listOf("video_smooth" to "false"), h.setCalls)
    }

    // Adopting is normalization, not an edit. Marking it dirty would raise a save prompt on the way
    // out of a menu the user only looked at.
    @Test
    fun `adopting a preset does not make the session look edited`() {
        val h = CuratedFakeHost()
        h.settings["video_smooth"] =
            RaSetting("video_smooth", "video_smooth", RaSettingType.BOOL, value = "?", rawValue = "?")
        val p = provider(h)
        p.screen(listOf("video"))
        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
    }

    @Test
    fun `an actual edit still raises the save prompt`() {
        val h = CuratedFakeHost()
        h.seed(row("video", "curated_screen_sharpness"), 0)
        val p = provider(h)
        p.screen(listOf("video"))
        p.cycle("curated_screen_sharpness", 1)
        assertTrue(p.exitPrompt() is IgmSettingsExit.Prompt)
    }

    // The whole reason RaSetting carries rawValue. aspect_ratio_index reports a translated label in
    // value and the index in rawValue, so resolution that read value would never match.
    @Test
    fun `resolution reads the raw value, not the display text`() {
        val h = CuratedFakeHost()
        val scaling = row("video", "curated_screen_scaling")
        for ((k, v) in scaling.presets[0].values) {
            val display = if (k == "aspect_ratio_index") "Core Provided" else v
            h.settings[k] = RaSetting(k, k, RaSettingType.ENUM, value = display, rawValue = v)
        }
        val r = choices(provider(h), listOf("video")).first { it.key == "curated_screen_scaling" }
        assertEquals("Core Reported", r.value)
    }

    @Test
    fun `cycling a composite writes every key the row owns`() {
        val h = CuratedFakeHost()
        val hud = row("advanced", "curated_debug_hud")
        h.seed(hud, 0)
        val p = provider(h)
        p.screen(listOf("advanced"))
        p.cycle("curated_debug_hud", 1)
        assertEquals(hud.settingKeys, h.setCalls.map { it.first }.toSet())
        for ((k, v) in hud.presets[1].values) {
            assertEquals("$k should carry the new preset's value", v, h.settings[k]?.rawValue)
        }
    }

    @Test
    fun `cycling a composite marks every key it wrote for the override`() {
        val h = CuratedFakeHost()
        val hud = row("advanced", "curated_debug_hud")
        h.seed(hud, 0)
        val p = provider(h)
        p.screen(listOf("advanced"))
        p.cycle("curated_debug_hud", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).onChoice(0)
        assertEquals(hud.settingKeys, h.savedKeys.single())
    }

    @Test
    fun `cycling updates the row shown without needing a reload`() {
        val h = CuratedFakeHost()
        h.seed(row("video", "curated_screen_sharpness"), 0)
        val p = provider(h)
        p.screen(listOf("video"))
        p.cycle("curated_screen_sharpness", 1)
        val r = choices(p, listOf("video")).first { it.key == "curated_screen_sharpness" }
        assertEquals("Soft", r.value)
    }

    // RetroArch does not register every key on every build. A key that never differs between
    // presets is normalization only, so losing it must not delete the row.
    @Test
    fun `a row survives a missing key that no preset varies`() {
        val h = CuratedFakeHost()
        val scaling = row("video", "curated_screen_scaling")
        h.seed(scaling, 0)
        h.settings.remove("video_scale_integer_overscale")
        val r = choices(provider(h), listOf("video")).firstOrNull { it.key == "curated_screen_scaling" }
        assertEquals("Core Reported", r?.value)
    }

    @Test
    fun `a row disappears when a key that distinguishes its presets is missing`() {
        val h = CuratedFakeHost()
        val scaling = row("video", "curated_screen_scaling")
        h.seed(scaling, 0)
        h.settings.remove("aspect_ratio_index")
        assertTrue(choices(provider(h), listOf("video")).none { it.key == "curated_screen_scaling" })
    }

    @Test
    fun `cycling does not write a key RetroArch does not expose`() {
        val h = CuratedFakeHost()
        h.seed(row("video", "curated_screen_scaling"), 0)
        h.settings.remove("video_scale_integer_overscale")
        val p = provider(h)
        p.screen(listOf("video"))
        p.cycle("curated_screen_scaling", 1)
        assertTrue(h.setCalls.none { it.first == "video_scale_integer_overscale" })
    }

    // raSetSetting is queued onto RetroArch's run loop, so a host that has not applied the write yet
    // still reports the old value. The row must show the new one immediately regardless.
    @Test
    fun `the row updates before RetroArch has applied the write`() {
        val h = CuratedFakeHost()
        h.seed(row("video", "curated_screen_sharpness"), 0)
        h.applyWrites = false
        val p = provider(h)
        p.screen(listOf("video"))
        p.cycle("curated_screen_sharpness", 1)
        val r = choices(p, listOf("video")).first { it.key == "curated_screen_sharpness" }
        assertEquals("Soft", r.value)
    }

    // The optimistic cache would otherwise keep showing a value RetroArch refused to apply, which
    // is exactly what a rejected combobox write looks like from here.
    @Test
    fun `a write RetroArch never applied is not remembered after leaving the category`() {
        val h = CuratedFakeHost()
        h.seed(row("video", "curated_screen_sharpness"), 0)
        h.applyWrites = false
        val p = provider(h)
        p.screen(listOf("video"))
        p.cycle("curated_screen_sharpness", 1)
        assertEquals("Soft", choices(p, listOf("video")).first().value)

        p.screen(emptyList())
        assertEquals("Sharp", choices(p, listOf("video")).first().value)
    }

    // The echo carries display text, so trusting its payload wrote "Core Provided" into a cache of
    // raw values and made every keypress resolve to Custom.
    @Test
    fun `an applied echo carrying display text does not poison the cache`() {
        val h = CuratedFakeHost()
        val scaling = row("video", "curated_screen_scaling")
        for ((k, v) in scaling.presets[0].values) {
            val display = if (k == "aspect_ratio_index") "Core Provided" else v
            h.settings[k] = RaSetting(k, k, RaSettingType.ENUM, value = display, rawValue = v)
        }
        val p = provider(h)
        p.screen(listOf("video"))
        assertEquals("Core Reported", choices(p, listOf("video")).first { it.key == scaling.key }.value)

        h.echoApplied("aspect_ratio_index", "Core Provided")
        assertEquals("Core Reported", choices(p, listOf("video")).first { it.key == scaling.key }.value)
    }

    @Test
    fun `cycling then receiving the echo keeps the new preset`() {
        val h = CuratedFakeHost()
        val scaling = row("video", "curated_screen_scaling")
        for ((k, v) in scaling.presets[0].values) {
            h.settings[k] = RaSetting(k, k, RaSettingType.ENUM, value = v, rawValue = v)
        }
        val p = provider(h)
        p.screen(listOf("video"))
        p.cycle(scaling.key, 1)
        // RetroArch applies the write, then echoes the display value for each key it changed.
        h.echoApplied("video_scale_integer", "OFF")
        assertEquals("Integer", choices(p, listOf("video")).first { it.key == scaling.key }.value)
    }

    @Test
    fun `info is absent when the host reports nothing`() {
        val h = CuratedFakeHost()
        assertTrue(provider(h).screen(emptyList()).items.none { it.key == CuratedCatalog.CATEGORY_INFO })
    }

    @Test
    fun `info lists what the host reports, in order, as read-only rows`() {
        val h = CuratedFakeHost()
        h.systemInfo = listOf("Core" to "Nestopia", "Version" to "1.52")
        val p = provider(h)
        assertTrue(p.screen(emptyList()).items.any { it.key == CuratedCatalog.CATEGORY_INFO })
        val rows = choices(p, listOf(CuratedCatalog.CATEGORY_INFO))
        assertEquals(listOf("Core", "Version"), rows.map { it.label })
        assertEquals(listOf("Nestopia", "1.52"), rows.map { it.value })
    }

    // Nothing on the Info screen is a setting, so a stray cycle must not reach the RetroArch path
    // and write something named after a label.
    @Test
    fun `cycling an info row does nothing`() {
        val h = CuratedFakeHost()
        h.systemInfo = listOf("Core" to "Nestopia")
        val p = provider(h)
        p.screen(listOf(CuratedCatalog.CATEGORY_INFO))
        p.cycle("info_0", 1)
        assertTrue(h.setCalls.isEmpty())
    }

    // Info describes the running core, which is worth having in either menu.
    @Test
    fun `the all-settings menu also offers info`() {
        val h = CuratedFakeHost()
        h.systemInfo = listOf("Core" to "Nestopia", "Version" to "1.52")
        val all = RaIgmSettingsProvider(
            host = h, strings = RaOptionStrings(), curated = false,
        )
        assertTrue(all.screen(emptyList()).items.any { it.key == CuratedCatalog.CATEGORY_INFO })
        val rows = all.screen(listOf(CuratedCatalog.CATEGORY_INFO)).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>()
        assertEquals(listOf("Core", "Version"), rows.map { it.label })
        assertEquals(listOf("Nestopia", "1.52"), rows.map { it.value })
    }

    @Test
    fun `the all-settings menu omits info when the host reports nothing`() {
        val h = CuratedFakeHost()
        val all = RaIgmSettingsProvider(
            host = h, strings = RaOptionStrings(), curated = false,
        )
        assertTrue(all.screen(emptyList()).items.none { it.key == CuratedCatalog.CATEGORY_INFO })
    }

    @Test
    fun `the everything menu is unaffected by the curated catalog`() {
        val h = CuratedFakeHost()
        val everything = RaIgmSettingsProvider(
            host = h, strings = RaOptionStrings(), curated = false,
        )
        h.screens[""] = listOf(RaScreenRow("latency_settings", "Latency", isMenu = true))
        val keys = everything.screen(emptyList()).items.map { it.key }
        assertTrue(keys.contains("latency_settings"))
        assertTrue(keys.none { it.startsWith("curated_") })
    }
}
