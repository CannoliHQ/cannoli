package dev.cannoli.igm

private const val LOCAL_TOGGLE_PREFIX = "cannoli_"
private const val RA_MENU_KEY = "__ra_menu__"

class RaIgmSettingsProvider(
    private val host: RaSettingsHost,
    private val strings: RaOptionStrings = RaOptionStrings(),
    private val onOpenNativeMenu: () -> Unit,
) : IgmSettingsProvider {

    private var onChanged: (() -> Unit)? = null
    private var dirty = false

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

    override fun screen(path: List<String>): GenericIgmSettingsScreen = when (val category = path.firstOrNull()) {
        null -> root()
        else -> categoryScreen(category)
    }

    private fun root(): GenericIgmSettingsScreen {
        val items = buildList {
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
        val category = RaOptionCatalog.categories.first { it.key == categoryKey }
        pending.clear()
        currentCategory = categoryKey
        currentSettings = category.settingKeys.mapNotNull { key ->
            if (key.startsWith(LOCAL_TOGGLE_PREFIX)) {
                RaSetting(
                    key = key,
                    label = strings.localToggleLabels[key] ?: key,
                    type = RaSettingType.BOOL,
                    value = if (host.getLocalToggle(key, true)) "true" else "false",
                )
            } else {
                host.raGetSetting(key)
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
                dirty = false
                onOpenNativeMenu()
            },
        ) { choice ->
            when (choice) {
                0 -> host.raSaveOverride(RaOverrideScope.CONTENT_DIR)
                1 -> host.raSaveOverride(RaOverrideScope.GAME)
            }
            dirty = false
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
                0 -> host.raSaveOverride(RaOverrideScope.CONTENT_DIR)
                1 -> host.raSaveOverride(RaOverrideScope.GAME)
            }
            dirty = false
        }
}
