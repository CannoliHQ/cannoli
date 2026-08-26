package dev.cannoli.igm

import dev.cannoli.core.CheevosSessionKeys

private const val EMULATOR_CATEGORY = "emulator"
private const val INFO_ROW_PREFIX = "info_"

// What RetroArch has a settings_show_ flag for is turned off in the launch config instead, where
// the decision rides stable config keys rather than menu label strings. What is left is sub-screens
// with no flag of their own, which is the only place Cannoli still refuses anything by name.
//
// The cost of a wrong entry here is small and visible: a screen we meant to hide simply appears.
internal val HIDDEN_SCREENS = setOf(
    // No mixer and nothing to make menu sounds for.
    "audio_mixer_settings",
    "menu_sounds",
    // No MIDI path on these devices.
    "midi_settings",
    // Desktop concepts. Android is always fullscreen, and video_fullscreen off mid-game would
    // leave the player with nothing to look at.
    "video_fullscreen_mode_settings",
    "video_windowed_mode_settings",
    // Inert: the HDR enable key has no _STR define, so menu_setting_find can never resolve it.
    "video_hdr_settings",
    // Cannoli owns core delivery; this is buildbot URLs and updater backups.
    "updater_settings",
    // Android owns the radios, and disconnect_wifi from a game menu could drop the session.
    "wifi_settings",
    "wifi_network_scan",
    "bluetooth_settings",
    "lakka_services",
)

// The one place All Settings adds a row RetroArch did not put there. Both live on RetroArch's Core
// screen, which Cannoli hides, and both are about video rather than core management, so Video is
// where they belong. Keyed by screen so a promoted row lands somewhere a player would look for it.
internal val PROMOTED_KEYS = mapOf(
    "video_settings" to listOf("video_shared_context", "video_allow_rotate"),
)

internal val HIDDEN_KEYS = setOf(
    "input_driver",
    "input_joypad_driver",
    "menu_driver",
    "audio_mixer_mute_enable",
    // Both are PATH rows, which RaValueCycler cannot cycle, so they render as a value that does
    // nothing. filter_enable only matters when video_filter names a filter, which it cannot here.
    "audio_dsp_plugin",
    "video_filter",
    "filter_enable",
    // Cannoli decides cutout handling from the launch config.
    "video_notch_write_over",
)

class RaIgmSettingsProvider(
    private val host: RaSettingsHost,
    private val strings: RaOptionStrings = RaOptionStrings(),
    private val curated: Boolean = false,
) : IgmSettingsProvider {

    private var onChanged: (() -> Unit)? = null
    private var dirty = false

    // RA setting keys the user changed this session, saved as the override. Local toggles never
    // land here (they write straight to SharedPreferences); core-option keys may, and the native
    // side drops any key that is not a live RA setting. Cleared whenever the dirty flag is.
    private val changedKeys = mutableSetOf<String>()

    // The settings of the category currently being shown, cached so cycle() has the
    // rich RaSetting (type/min/max/options) the generic Choice row does not carry.
    private var currentCategory: String? = null
    private var currentSettings: List<RaSetting> = emptyList()

    // Counts outstanding raSetSetting calls per key so the async apply echo for our own
    // change is swallowed instead of being treated as an external update.
    private val pending = mutableMapOf<String, Int>()

    init {
        host.setOnRaSettingApplied { key, value -> onApplied(key, value) }
    }

    override fun setOnChanged(callback: () -> Unit) { onChanged = callback }

    override fun screen(path: List<String>): GenericIgmSettingsScreen = when {
        path.isEmpty() -> if (curated) curatedRoot() else root()
        path.first() == EMULATOR_CATEGORY -> emulatorScreen(path.getOrNull(1))
        // Info describes the running core rather than any setting, so it belongs in both menus.
        path.first() == CuratedCatalog.CATEGORY_INFO -> infoScreen()
        curated -> curatedCategoryScreen(path.first())
        // RetroArch's tree is arbitrarily deep, so the screen is whatever the path last entered.
        else -> raScreen(path.last())
    }

    // A curated row is reachable only when every RetroArch key it writes exists on this build, and
    // a category with no reachable row is left out rather than shown empty, the same way the
    // Emulator row is omitted for a core that exposes no options.
    private fun reachableRows(category: CuratedCatalog.Category): List<CuratedCatalog.Row> =
        category.rows.filter { row -> row.discriminatingKeys.all { host.raGetSetting(it) != null } }

    // raSetSetting enqueues onto RetroArch's run loop, so a read straight after a write still
    // returns the old value. The Everything path solves this by updating its cached row optimistically
    // and swallowing the async echo through `pending`; curated rows cache the same way rather than
    // re-reading the host on every render, which is what left a row showing its previous value until
    // you navigated away and back.
    private var curatedValues: MutableMap<String, String> = mutableMapOf()
    private var curatedCategory: String? = null

    private fun loadCurated(category: CuratedCatalog.Category) {
        if (curatedCategory == category.key) return
        curatedCategory = category.key
        pending.clear()
        val rows = reachableRows(category)
        val shadow = host.shadowedSettings()
        // A shadowed key holds the value Cannoli overwrote it with, not the user's choice, so it
        // is read from the shadow instead of the live host.
        curatedValues = rows
            .flatMap { it.settingKeys }
            .distinct()
            .mapNotNull { key -> (shadow[key] ?: host.raGetSetting(key)?.rawValue)?.let { key to it } }
            .toMap(mutableMapOf())
        for (row in rows) adoptFirstPresetIfUnmatched(row)
    }

    // Curated mode drives RetroArch rather than reporting on it, so a row whose live values match no
    // preset takes the first one instead of showing a state the menu cannot express. Deliberately
    // does NOT mark the session dirty: adopting is normalization, and making it look like an edit
    // would raise a save prompt on the way out of a menu the user only looked at. The adoption
    // therefore lasts the session unless the user actually changes something.
    private fun adoptFirstPresetIfUnmatched(row: CuratedCatalog.Row) {
        if (CuratedCatalog.resolve(row, valuesFor(row)) != null) return
        for ((key, value) in row.presets.first().values) {
            if (!curatedValues.containsKey(key)) continue
            if (!host.raSetSetting(key, value)) continue
            curatedValues[key] = value
            pending[key] = (pending[key] ?: 0) + 1
        }
    }

    private fun curatedRoot(): GenericIgmSettingsScreen {
        // Re-entering a category re-reads it. The cache is optimistic, so without this a write
        // RetroArch rejected would keep showing the value it never applied.
        curatedCategory = null
        val items = buildList {
            for (cat in CuratedCatalog.categories) {
                if (reachableRows(cat).isEmpty()) continue
                add(GenericIgmSettingsItem.Category(cat.key, curatedTitle(cat.key)))
            }
            if (host.coreOptions().isNotEmpty()) {
                add(GenericIgmSettingsItem.Category(
                    CuratedCatalog.CATEGORY_EMULATOR,
                    curatedTitle(CuratedCatalog.CATEGORY_EMULATOR),
                ))
            }
            if (host.systemInfo().isNotEmpty()) {
                add(GenericIgmSettingsItem.Category(
                    CuratedCatalog.CATEGORY_INFO,
                    curatedTitle(CuratedCatalog.CATEGORY_INFO),
                ))
            }
        }
        return GenericIgmSettingsScreen(strings.rootTitle, items)
    }

    // Read-only rows: nothing here is a setting, so the keys are positional and cycle() ignores them.
    private fun infoScreen(): GenericIgmSettingsScreen = GenericIgmSettingsScreen(
        curatedTitle(CuratedCatalog.CATEGORY_INFO),
        host.systemInfo().mapIndexed { i, (label, value) ->
            GenericIgmSettingsItem.Choice(key = "$INFO_ROW_PREFIX$i", label = label, value = value)
        },
    )

    private fun curatedCategoryScreen(categoryKey: String): GenericIgmSettingsScreen {
        if (categoryKey == CuratedCatalog.CATEGORY_INFO) return infoScreen()
        val cat = CuratedCatalog.categories.firstOrNull { it.key == categoryKey }
            ?: return GenericIgmSettingsScreen(curatedTitle(categoryKey), emptyList())
        loadCurated(cat)
        return GenericIgmSettingsScreen(
            curatedTitle(categoryKey),
            reachableRows(cat).map { row ->
                GenericIgmSettingsItem.Choice(
                    key = row.key,
                    label = strings.curatedRowLabels[row.key] ?: row.key,
                    value = CuratedCatalog.resolve(row, valuesFor(row))
                        ?.let { strings.curatedPresetLabels[it.labelKey] ?: it.labelKey }
                        ?: strings.custom,
                )
            },
        )
    }

    private fun curatedTitle(key: String) = strings.curatedCategoryTitles[key] ?: key

    private fun valuesFor(row: CuratedCatalog.Row): Map<String, String> =
        curatedValues.filterKeys { it in row.settingKeys }

    private fun cycleCurated(row: CuratedCatalog.Row, direction: Int) {
        val preset = CuratedCatalog.nextPreset(row, valuesFor(row), direction)
        var wrote = false
        // A key RetroArch does not expose is skipped rather than written blind, matching the
        // reachability rule that let this row exist without it.
        for ((key, value) in preset.values) {
            if (!curatedValues.containsKey(key)) continue
            if (!host.raSetSetting(key, value)) continue
            curatedValues[key] = value
            changedKeys.add(key)
            pending[key] = (pending[key] ?: 0) + 1
            wrote = true
        }
        if (wrote) {
            dirty = true
            onChanged?.invoke()
        }
    }

    // A core that declares categories gets a screen of them; one that does not gets its options
    // straight away, so a flat core is never a menu that leads to a single menu.
    private fun emulatorScreen(categoryKey: String?): GenericIgmSettingsScreen {
        val options = host.coreOptions()
        val cats = options.filter { it.categoryKey.isNotEmpty() }
            .distinctBy { it.categoryKey }
        if (categoryKey == null && cats.size > 1) {
            return GenericIgmSettingsScreen(
                strings.emulator,
                cats.map { GenericIgmSettingsItem.Category(it.categoryKey, it.categoryLabel.ifEmpty { it.categoryKey }) },
            )
        }
        val wanted = if (categoryKey == null) options else options.filter { it.categoryKey == categoryKey }
        // Reload only on a change of screen. screen() runs on every render, and a write is queued
        // onto the emulator thread, so reloading each time read the old value straight back over
        // the row the user had just changed.
        val cacheKey = if (categoryKey == null) EMULATOR_CATEGORY else "$EMULATOR_CATEGORY/$categoryKey"
        if (cacheKey != currentCategory) loadCoreOptions(wanted, cacheKey)
        val title = cats.firstOrNull { it.categoryKey == categoryKey }?.categoryLabel
            ?: strings.emulator
        return GenericIgmSettingsScreen(title, currentSettings.map(::rowFor))
    }

    private fun loadCoreOptions(refs: List<CoreOptionRef>, cacheKey: String) {
        pending.clear()
        currentCategory = cacheKey
        currentSettings = refs.mapNotNull { host.raGetSetting(it.key)?.let(::withRestartHint) }
    }

    private fun root(): GenericIgmSettingsScreen {
        val items = buildList {
            // Only cores that expose options get the row, so it is absent rather than empty.
            if (host.coreOptions().isNotEmpty()) {
                add(GenericIgmSettingsItem.Category(
                    EMULATOR_CATEGORY,
                    strings.emulator,
                ))
            }
            addAll(raRows(""))
            if (host.systemInfo().isNotEmpty()) {
                add(GenericIgmSettingsItem.Category(
                    CuratedCatalog.CATEGORY_INFO,
                    curatedTitle(CuratedCatalog.CATEGORY_INFO),
                ))
            }
        }
        return GenericIgmSettingsScreen(strings.rootTitle, items)
    }

    // Titles come from the parent row that led here, which is the only place RetroArch names a
    // screen. A screen is only reachable through its parent, so this is always populated first.
    private val screenTitles = mutableMapOf<String, String>()

    private fun raScreen(label: String): GenericIgmSettingsScreen =
        GenericIgmSettingsScreen(screenTitles[label] ?: label, raRows(label))

    /**
     * One RetroArch settings screen, rendered in RetroArch's own order. RetroArch decides which
     * rows exist right now, so the conditional ones it hides (video_aspect_ratio unless the aspect
     * index is Config, the integer-scaling rows unless integer scaling is on) are hidden here too,
     * without Cannoli modelling any of those conditions.
     *
     * Values are still read through the host, not from the row: both menu modes share one read
     * path, one write path and one changed-key set.
     */
    private fun raRows(label: String): List<GenericIgmSettingsItem> {
        val rows = host.raScreenRows(label)
            .filterNot { it.key in HIDDEN_SCREENS || it.key in HIDDEN_KEYS }
        rows.filter { it.isMenu }.forEach { screenTitles[it.key] = it.label }
        val promoted = PROMOTED_KEYS[label].orEmpty()
        loadKeys(rows.filterNot { it.isMenu }.map { it.key } + promoted, "ra/$label")
        // Walked in RetroArch's order rather than settings-then-submenus, because the order is part
        // of what we are deferring to it.
        return rows.mapNotNull { row ->
            if (row.isMenu) GenericIgmSettingsItem.Category(row.key, row.label)
            else currentSettings.firstOrNull { it.key == row.key }?.let(::rowFor)
        } + promoted.mapNotNull { key -> currentSettings.firstOrNull { it.key == key }?.let(::rowFor) }
    }

    // Cache key is the RetroArch screen, so a parent and a child never share a slot and moving
    // between them reloads rather than showing the other's rows.
    //
    // The key list is part of the cache identity, not just the screen: RetroArch's rows are
    // conditional, so setting aspect_ratio_index to Config reveals video_aspect_ratio on a screen
    // we are already standing on. Keying only on the screen left those rows out of currentSettings
    // and mapNotNull then dropped them, so a row RetroArch had just revealed never appeared.
    //
    // Rows already loaded keep their cached RaSetting rather than being re-read. raSetSetting is
    // asynchronous, so a re-read right after a cycle returns the old value and the row would flick
    // back to what the user just changed it from.
    private fun loadKeys(keys: List<String>, cacheKey: String) {
        if (cacheKey == currentCategory && keys == currentKeys) return
        if (cacheKey != currentCategory) pending.clear()
        currentCategory = cacheKey
        currentKeys = keys
        val cached = currentSettings.associateBy { it.key }
        currentSettings = keys.mapNotNull { key ->
            cached[key] ?: host.raGetSetting(key)?.let(::withRestartHint)
        }
    }

    private var currentKeys: List<String> = emptyList()

    private fun rowFor(s: RaSetting) = GenericIgmSettingsItem.Choice(
        key = s.key,
        label = s.label,
        value = when {
            s.type == RaSettingType.BOOL && s.value == "true" -> strings.on
            s.type == RaSettingType.BOOL -> strings.off
            else -> s.value
        },
        hint = if (s.requiresRestart) strings.restartHint else null,
        description = s.description,
    )

    override fun cycle(itemKey: String, direction: Int) {
        if (itemKey.startsWith(INFO_ROW_PREFIX)) return
        CuratedCatalog.rowFor(itemKey)?.let { return cycleCurated(it, direction) }
        val i = currentSettings.indexOfFirst { it.key == itemKey }
        if (i < 0) return
        val s = currentSettings[i]
        val newValue = RaValueCycler.next(s, direction) ?: return
        if (newValue == s.value) return
        if (host.raSetSetting(s.key, newValue)) {
            dirty = true
            changedKeys.add(s.key)
            pending[s.key] = (pending[s.key] ?: 0) + 1
            replaceSetting(i, s.copy(value = newValue))
        }
    }

    private fun replaceSetting(index: Int, updated: RaSetting) {
        currentSettings = currentSettings.toMutableList().also { it[index] = updated }
        onChanged?.invoke()
    }

    private fun onApplied(key: String, value: String) {
        val remaining = (pending[key] ?: 0) - 1
        if (remaining > 0) {
            pending[key] = remaining
            return
        }
        pending.remove(key)
        // A curated row caches raw values, so an echo has to land there too or the row keeps showing
        // what it last wrote. The echo's payload is the DISPLAY value though, which for a combobox
        // is translated label text, so it is treated as a signal that something changed and the raw
        // value is read back rather than trusted. Storing the payload here poisoned the cache and
        // made every keypress resolve to Custom.
        if (curated && curatedValues.containsKey(key)) {
            val raw = host.raGetSetting(key)?.rawValue ?: return
            if (curatedValues[key] != raw) {
                curatedValues[key] = raw
                onChanged?.invoke()
            }
            return
        }
        val i = currentSettings.indexOfFirst { it.key == key }
        if (i < 0 || currentSettings[i].value == value) return
        replaceSetting(i, currentSettings[i].copy(value = value))
    }

    override fun activate(itemKey: String): IgmSettingsExit.Prompt? = null

    override fun exitPrompt(): IgmSettingsExit =
        if (!dirty) IgmSettingsExit.Close
        else IgmSettingsExit.Prompt(
            title = null,
            options = listOf(strings.savePlatform, strings.saveGame, strings.dontSave),
        ) { choice ->
            when (choice) {
                0 -> host.raSaveOverride(RaOverrideScope.SYSTEM, overrideKeys())
                1 -> host.raSaveOverride(RaOverrideScope.GAME, overrideKeys())
            }
            clearDirty()
        }

    // The RetroAchievements session keys are injected fresh into the per-launch config every launch
    // and must never be persisted in an override, where a stale copy could re-enable hardcore
    // against a forced softcore. RaOptionCatalog exposes none of them today, so this is defence in
    // depth, dropping any that reached the changed set before it crosses to the native writer.
    private fun overrideKeys(): Set<String> = changedKeys - CheevosSessionKeys.ALL

    private fun clearDirty() {
        dirty = false
        changedKeys.clear()
    }
}

// Cores put the restart notice in the option description themselves, which eats row width and
// repeats on every affected row. The flag drives the hint the screen already shows for the
// selected row, so the words come off the label.
private val RESTART_SUFFIX = Regex("""[\s\-–—]*\(\s*(?:restart|reboot)\s*\)\s*$""", RegexOption.IGNORE_CASE)

internal fun withRestartHint(s: RaSetting): RaSetting =
    if (RESTART_SUFFIX.containsMatchIn(s.label)) {
        s.copy(label = s.label.replace(RESTART_SUFFIX, ""), requiresRestart = true)
    } else {
        s
    }
