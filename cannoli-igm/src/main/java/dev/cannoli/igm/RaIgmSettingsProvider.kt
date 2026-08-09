package dev.cannoli.igm

private const val LOCAL_TOGGLE_PREFIX = "cannoli_"
private const val RA_MENU_KEY = "__ra_menu__"
private const val EMULATOR_CATEGORY = "emulator"

class RaIgmSettingsProvider(
    private val host: RaSettingsHost,
    private val strings: RaOptionStrings = RaOptionStrings(),
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
        path.isEmpty() -> root()
        path.first() == EMULATOR_CATEGORY -> emulatorScreen(path.getOrNull(1))
        else -> categoryScreen(path.first())
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
            add(GenericIgmSettingsItem.Action(RA_MENU_KEY, strings.nativeMenu))
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
        val i = currentSettings.indexOfFirst { it.key == key }
        if (i < 0 || currentSettings[i].value == value) return
        replaceSetting(i, currentSettings[i].copy(value = value))
    }

    override fun activate(itemKey: String): IgmSettingsExit.Prompt? {
        if (itemKey != RA_MENU_KEY) return null
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
                0 -> host.raSaveOverride(RaOverrideScope.CONTENT_DIR, changedKeys.toSet())
                1 -> host.raSaveOverride(RaOverrideScope.GAME, changedKeys.toSet())
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
                0 -> host.raSaveOverride(RaOverrideScope.CONTENT_DIR, changedKeys.toSet())
                1 -> host.raSaveOverride(RaOverrideScope.GAME, changedKeys.toSet())
            }
            clearDirty()
        }

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
