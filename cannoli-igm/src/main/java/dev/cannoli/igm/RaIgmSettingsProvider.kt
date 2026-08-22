package dev.cannoli.igm

import dev.cannoli.core.CheevosSessionKeys

private const val LOCAL_TOGGLE_PREFIX = "cannoli_"
private const val RA_MENU_KEY = "__ra_menu__"
private const val EMULATOR_CATEGORY = "emulator"
private const val INFO_ROW_PREFIX = "info_"

class RaIgmSettingsProvider(
    private val host: RaSettingsHost,
    private val strings: RaOptionStrings = RaOptionStrings(),
    private val debugBuild: Boolean = false,
    private val curated: Boolean = false,
    private val onOpenNativeMenu: () -> Unit,
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
        curated -> curatedCategoryScreen(path.first())
        else -> categoryScreen(path.first())
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
        curatedValues = rows
            .flatMap { it.settingKeys }
            .distinct()
            .mapNotNull { key -> host.raGetSetting(key)?.rawValue?.let { key to it } }
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
                strings.categoryTitles[EMULATOR_CATEGORY] ?: EMULATOR_CATEGORY,
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
            ?: strings.categoryTitles[EMULATOR_CATEGORY] ?: EMULATOR_CATEGORY
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
                    strings.categoryTitles[EMULATOR_CATEGORY] ?: EMULATOR_CATEGORY,
                ))
            }
            for (cat in RaOptionCatalog.categories) {
                add(GenericIgmSettingsItem.Category(cat.key, strings.categoryTitles[cat.key] ?: cat.key))
            }
            if (debugBuild) add(GenericIgmSettingsItem.Action(RA_MENU_KEY, strings.nativeMenu))
        }
        return GenericIgmSettingsScreen(strings.rootTitle, items)
    }

    private fun categoryScreen(categoryKey: String): GenericIgmSettingsScreen {
        if (categoryKey != currentCategory) loadCategory(categoryKey)
        val title = strings.categoryTitles[categoryKey] ?: categoryKey
        return GenericIgmSettingsScreen(title, currentSettings.map(::rowFor))
    }

    private fun loadCategory(categoryKey: String) {
        pending.clear()
        currentCategory = categoryKey
        val keys = RaOptionCatalog.categories.first { it.key == categoryKey }.settingKeys
        currentSettings = keys.mapNotNull { key ->
            if (key.startsWith(LOCAL_TOGGLE_PREFIX)) {
                RaSetting(
                    key = key,
                    label = strings.localToggleLabels[key] ?: key,
                    type = RaSettingType.BOOL,
                    value = if (host.getLocalToggle(key, true)) "true" else "false",
                )
            } else {
                host.raGetSetting(key)?.let(::withRestartHint)
            }
        }
    }

    private fun rowFor(s: RaSetting) = GenericIgmSettingsItem.Choice(
        key = s.key,
        label = s.label,
        value = when {
            s.type == RaSettingType.BOOL && s.value == "true" -> strings.on
            s.type == RaSettingType.BOOL -> strings.off
            else -> s.value
        },
        hint = if (s.requiresRestart) strings.restartHint else null,
    )

    override fun cycle(itemKey: String, direction: Int) {
        if (itemKey.startsWith(INFO_ROW_PREFIX)) return
        CuratedCatalog.rowFor(itemKey)?.let { return cycleCurated(it, direction) }
        val i = currentSettings.indexOfFirst { it.key == itemKey }
        if (i < 0) return
        val s = currentSettings[i]
        val newValue = RaValueCycler.next(s, direction) ?: return
        if (newValue == s.value) return
        if (s.key.startsWith(LOCAL_TOGGLE_PREFIX)) {
            host.setLocalToggle(s.key, newValue == "true")
            replaceSetting(i, s.copy(value = newValue))
        } else if (host.raSetSetting(s.key, newValue)) {
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

    override fun activate(itemKey: String): IgmSettingsExit.Prompt? {
        if (!debugBuild || itemKey != RA_MENU_KEY) return null
        if (!dirty) {
            onOpenNativeMenu()
            return null
        }
        // Unsaved RA changes: prompt to save first, then open the native menu, matching
        // the old north-button shortcut (IGMController handleRaOptionsKey keycode 100).
        return IgmSettingsExit.Prompt(
            title = null,
            options = listOf(strings.savePlatform, strings.saveGame, strings.dontSave),
            onCancel = {
                clearDirty()
                onOpenNativeMenu()
            },
        ) { choice ->
            when (choice) {
                0 -> host.raSaveOverride(RaOverrideScope.SYSTEM, overrideKeys())
                1 -> host.raSaveOverride(RaOverrideScope.GAME, overrideKeys())
            }
            clearDirty()
            onOpenNativeMenu()
        }
    }

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
