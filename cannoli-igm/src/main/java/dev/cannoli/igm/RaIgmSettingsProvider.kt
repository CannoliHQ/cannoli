package dev.cannoli.igm

import dev.cannoli.core.CheevosSessionKeys

import dev.cannoli.core.shader.PresetPass
import dev.cannoli.core.shader.PresetParameter
import dev.cannoli.core.shader.ShaderPragma
import dev.cannoli.core.shader.ShaderPreset

private const val EMULATOR_CATEGORY = "emulator"
private const val INFO_ROW_PREFIX = "info_"

// The pipeline tree's own path segments and row keys. Prefixed so nothing here can collide with a
// RetroArch setting key, which shares the same namespace once a row reaches cycle().
private const val SHADER_LOAD = "load"
private const val SHADER_ENABLED = "enabled"
private const val SHADER_SAVE = "save"
private const val SHADER_APPEND = "append"
private const val SHADER_PREPEND = "prepend"
private const val SHADER_PARAMS = "params"
private const val SHADER_BROWSE = "browse"

// Preset keys a pass owns that the menu shows. Scale is four keys because a size means nothing
// without saying what it is relative to.
private const val KEY_FILTER = "filter_linear"
private const val KEY_SCALE_X = "scale_x"
private const val KEY_SCALE_Y = "scale_y"
private const val KEY_SCALE_TYPE_X = "scale_type_x"
private const val KEY_SCALE_TYPE_Y = "scale_type_y"
private const val SHADER_PASS_PREFIX = "pass_"
private const val SHADER_PARAM_PREFIX = "shader_param:"
private const val SHADER_FILTER_PREFIX = "shader_filter:"
private const val SHADER_SCALE_PREFIX = "shader_scale:"

// Cannoli's own key for the shader in force, staged so a pipeline edit reaches the save prompt.
private val SHADER_KEY_CANNOLI = dev.cannoli.core.config.OverrideTiers.KEY_SHADER

// A scale row has to be walkable with a D-pad, and past this it is a preset's business rather
// than something set by hand. The pass count has no such limit here: it follows from what is
// loaded, and RetroArch caps it at GFX_MAX_SHADERS.
private const val MAX_SHADER_SCALE = 8

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

    // Marks a browser row as a preset rather than a folder, since both are just names.
    private val SHADER_PRESET_PREFIX = "shader_preset:"

    // What each changed key held before the first edit of this visit, so Discard can put it back.
    // Captured before the write, since afterwards the old value is gone, and first capture wins,
    // so cycling a row four times still restores what it held on the way in. Only user edits are
    // recorded: adoptFirstPresetIfUnmatched normalises on load and never dirties, so the value it
    // replaces is not something Discard should bring back.
    /** What Discard puts back, so it is the machine value and cannot be anything else. */
    private val priorValues = mutableMapOf<String, MachineValue>()

    // The settings of the category currently being shown, cached so cycle() has the
    // rich RaSetting (type/min/max/options) the generic Choice row does not carry.
    private var currentCategory: String? = null
    private var currentSettings: List<RaSetting> = emptyList()

    // Counts outstanding raSetSetting calls per key so the async apply echo for our own
    // change is swallowed instead of being treated as an external update.
    private val pending = mutableMapOf<String, Int>()

    init {
        // The payload is dropped here: it is RetroArch's display text, so it can say a setting
        // changed but never what it changed to.
        host.setOnRaSettingApplied { key, _ -> onApplied(key) }
    }

    override fun setOnChanged(callback: () -> Unit) { onChanged = callback }

    // The level the navigator last asked for. activate() is told only a row key, and a shader
    // preset means nothing without the folder it was listed from.
    private var currentPath: List<String> = emptyList()

    override fun screen(path: List<String>): GenericIgmSettingsScreen {
        currentPath = path
        return screenFor(path)
    }

    private fun screenFor(path: List<String>): GenericIgmSettingsScreen = when {
        path.isEmpty() -> if (curated) curatedRoot() else root()
        path.first() == EMULATOR_CATEGORY -> emulatorScreen(path.getOrNull(1))
        // Info describes the running core rather than any setting, so it belongs in both menus.
        path.first() == CuratedCatalog.CATEGORY_INFO -> infoScreen()
        // Cannoli's own screen, so the row only marks the way in: the controller swaps to the
        // live preview picker on seeing this path and never renders what is returned here.
        path.first() == CuratedCatalog.CATEGORY_OVERLAY ->
            GenericIgmSettingsScreen(curatedTitle(CuratedCatalog.CATEGORY_OVERLAY), emptyList())
        // One row, which hands off to Cannoli's own screen. The category exists so shortcuts sit
        // where the rest of a platform's settings sit rather than in the menu above them.
        path.first() == CuratedCatalog.CATEGORY_INPUT && path.size == 1 ->
            GenericIgmSettingsScreen(
                curatedTitle(CuratedCatalog.CATEGORY_INPUT),
                listOf(GenericIgmSettingsItem.Category(
                    CuratedCatalog.INPUT_SHORTCUTS,
                    strings.shortcuts,
                )),
            )
        path.first() == CuratedCatalog.CATEGORY_INPUT ->
            GenericIgmSettingsScreen(strings.shortcuts, emptyList())
        path.first() == CuratedCatalog.CATEGORY_SHADER ->
            if (curated) shaderScreen(path.drop(1)) else shaderPipelineScreen(path.drop(1))
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
            .mapNotNull { key -> (shadow[key] ?: host.raGetSetting(key)?.machineValue?.raw)?.let { key to it } }
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
            if (!host.raSetSetting(key, MachineValue(value))) continue
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
                // Beside Video rather than after everything: a bezel and a shader are how the
                // picture looks, which is what the rows above them are, and they are the two people
                // come here for. Below Video, so the size and shape of the image is settled first.
                if (cat.key == CuratedCatalog.CATEGORY_VIDEO) {
                    addOverlayCategory()
                    addShaderCategory()
                    addInputCategory()
                }
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

    /** The scopes holding something to drop, nearest first, so the narrower answer is the default. */
    private fun resetScopes(): List<RaOverrideScope> =
        listOf(RaOverrideScope.GAME, RaOverrideScope.SYSTEM).filter { host.hasOverrides(it) }

    // The root only. Deeper levels are one category of a scope, and a scope is stored whole, so
    // offering it from inside Video would throw away Input as well without saying so. Both roots
    // qualify: they write the same two tiers, so undoing them is one question from either.
    override fun canReset(path: List<String>): Boolean =
        path.isEmpty() && resetScopes().isNotEmpty()

    /**
     * Undoes exactly what the save prompt does, one scope at a time.
     *
     * A scope with nothing stored is left off rather than shown doing nothing, which is the failure
     * this exists to fix rather than repeat.
     */
    override fun resetPrompt(path: List<String>): IgmSettingsExit.Prompt? {
        if (!canReset(path)) return null
        return IgmSettingsExit.Prompt(
            title = strings.resetTitle,
            options = resetScopes().map { scope ->
                val label = if (scope == RaOverrideScope.GAME) strings.resetGame else strings.resetPlatform
                IgmPromptOption(label) { reset(scope) }
            },
        )
    }

    // Dropped rather than kept: this visit's edits were to the tier that just went, so saving them
    // afterwards would write it straight back.
    private fun reset(scope: RaOverrideScope) {
        host.resetOverrides(scope)
        clearDirty()
    }

    /**
     * One level of the shader browser. Folders are Category rows, so descending and backing out ride
     * the path stack every other category already uses; presets are Action rows, because choosing
     * one hands off to the picker rather than opening another screen.
     *
     * The title is the folder rather than a fixed word, so a browser several levels into a pack
     * still says where it is.
     */
    private fun shaderScreen(relative: List<String>): GenericIgmSettingsScreen {
        val title = relative.lastOrNull() ?: curatedTitle(CuratedCatalog.CATEGORY_SHADER)
        val applied = host.appliedShaderPreset()
        return GenericIgmSettingsScreen(
            title,
            host.shaderEntries(relative).map { entry ->
                when {
                    entry.isFolder -> GenericIgmSettingsItem.Category(entry.name, entry.name)
                    // Only in the picker, where the list is the answer and has to say which one is
                    // in force. In the chain tree the browser is a question you leave once it is
                    // answered, so marking one is both wrong and never seen.
                    curated && entry.path == applied ->
                        GenericIgmSettingsItem.Choice(
                            SHADER_PRESET_PREFIX + entry.name,
                            entry.name,
                            strings.shaderApplied,
                        )
                    else -> GenericIgmSettingsItem.Action(SHADER_PRESET_PREFIX + entry.name, entry.name)
                }
            },
        )
    }

    /**
     * The shader chain All Settings edits, held here rather than in RetroArch's live shader.
     *
     * RetroArch is handed the result once, when the chain is applied or saved.
     */
    private fun shaderPipelineScreen(relative: List<String>): GenericIgmSettingsScreen = when {
        relative.isEmpty() -> shaderPipelineRoot()
        relative.first() == SHADER_LOAD -> shaderScreen(relative.drop(1))
        relative.first() == SHADER_APPEND -> shaderScreen(relative.drop(1))
        relative.first() == SHADER_PREPEND -> shaderScreen(relative.drop(1))
        // Save is Cannoli's own screen: the controller swaps to the keyboard on seeing this path
        // and never renders what is returned here, the same way the overlay row works.
        relative.first() == SHADER_SAVE -> GenericIgmSettingsScreen(strings.shaderSave, emptyList())
        relative.first() == SHADER_PARAMS -> shaderParametersScreen()
        relative.first().startsWith(SHADER_PASS_PREFIX) -> shaderPassScreen(relative)
        else -> GenericIgmSettingsScreen(curatedTitle(CuratedCatalog.CATEGORY_SHADER), emptyList())
    }

    /** Seeded from the preset in force, so entering the tree shows what is already running. */
    private fun chain(): ShaderPreset {
        chain?.let { return it }
        val seeded = host.appliedShaderPreset()
            ?.let { ShaderPreset.parse(java.io.File(it)) }
            ?: ShaderPreset()
        chain = seeded
        host.setShaderChain(seeded)
        return seeded
    }

    private var chain: ShaderPreset? = null

    private fun editChain(edited: ShaderPreset) {
        chain = edited
        parameterCache = null
        host.setShaderChain(edited)
        stageChainEdit()
    }

    // A file read per pass, and the root asks how many there are on every render.
    private var parameterCache: List<PresetParameter>? = null

    private fun parameters(): List<PresetParameter> =
        parameterCache ?: ShaderPragma.parameters(chain().passes).also { parameterCache = it }

    private fun shaderPipelineRoot(): GenericIgmSettingsScreen {
        val title = curatedTitle(CuratedCatalog.CATEGORY_SHADER)
        val chain = chain()
        // Only once there is something to switch, which is also the case that needs it: a shader
        // turned off by the shortcut leaves an empty chain, and without this row the screen offers
        // nothing but Load Preset and no way back to what was already set up.
        val applied = host.appliedShaderPreset() != null
        val toggle = if (applied || host.shaderToRestore() != null) {
            GenericIgmSettingsItem.Choice(
                SHADER_ENABLED,
                strings.shaderEnabled,
                if (applied) strings.on else strings.off,
            )
        } else null

        // Switched off, the switch is the whole screen. Everything below it describes a chain the
        // game is not running, so loading into it or tuning its passes would edit nothing visible.
        if (toggle != null && !applied) return GenericIgmSettingsScreen(title, listOf(toggle))

        val items = buildList {
            toggle?.let { add(it) }
            add(GenericIgmSettingsItem.Category(SHADER_LOAD, strings.shaderLoad))
            // With no chain these are Load Preset by another name.
            if (chain.passes.isNotEmpty()) {
                add(GenericIgmSettingsItem.Category(SHADER_PREPEND, strings.shaderAddStart))
                add(GenericIgmSettingsItem.Category(SHADER_APPEND, strings.shaderAddEnd))
                add(GenericIgmSettingsItem.Category(SHADER_SAVE, strings.shaderSave))
            }
            // Named, or a chain of four is four rows saying only their position.
            chain.passes.forEachIndexed { i, pass ->
                add(GenericIgmSettingsItem.Category(
                    SHADER_PASS_PREFIX + i,
                    strings.shaderPassNamed(i, passName(pass)),
                ))
            }
            // Absent rather than empty, which would read as the tunables having failed to load.
            if (parameters().isNotEmpty()) {
                add(GenericIgmSettingsItem.Category(SHADER_PARAMS, strings.shaderParameters))
            }
        }
        return GenericIgmSettingsScreen(title, items)
    }

    private fun passName(pass: PresetPass): String =
        pass.shader.substringAfterLast('/').substringBeforeLast('.')
            .takeIf { it.isNotEmpty() } ?: strings.shaderNone

    private fun shaderParametersScreen(): GenericIgmSettingsScreen =
        GenericIgmSettingsScreen(
            strings.shaderParameters,
            parameters().map { p ->
                GenericIgmSettingsItem.Choice(
                    SHADER_PARAM_PREFIX + p.id,
                    p.desc,
                    formatParameter(parameterValue(p)),
                )
            },
        )

    /** What the chain says, or the shader author's default. */
    private fun parameterValue(p: PresetParameter): Float =
        chain().parameters[p.id]?.toFloatOrNull() ?: p.default

    // A parameter stepping by 1 reads as 2, not 2.00; one stepping by 0.05 keeps its digits.
    private fun formatParameter(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString()
        else "%.2f".format(value).trimEnd('0').trimEnd('.')

    private fun shaderPassScreen(relative: List<String>): GenericIgmSettingsScreen {
        val index = relative.first().removePrefix(SHADER_PASS_PREFIX).toIntOrNull()
            ?: return GenericIgmSettingsScreen(strings.shaderPreset, emptyList())
        // Under the pass, so picking sets this pass rather than replacing the chain.
        if (relative.getOrNull(1) == SHADER_BROWSE) return shaderScreen(relative.drop(2))
        val pass = chain().passes.getOrNull(index)
            ?: return GenericIgmSettingsScreen(strings.shaderPass(index), emptyList())
        return GenericIgmSettingsScreen(
            strings.shaderPass(index),
            listOf(
                GenericIgmSettingsItem.Category(SHADER_BROWSE, strings.shaderPreset),
                GenericIgmSettingsItem.Choice(
                    SHADER_FILTER_PREFIX + index,
                    strings.shaderFilter,
                    filterLabel(pass.settings[KEY_FILTER]),
                ),
                GenericIgmSettingsItem.Choice(
                    SHADER_SCALE_PREFIX + index,
                    strings.shaderScale,
                    scaleLabel(pass.settings[KEY_SCALE_X]),
                ),
            ),
        )
    }

    // Absent is a third state: the preset leaving the choice alone.
    private fun filterLabel(value: String?): String = when (value) {
        "true" -> strings.shaderFilterLinear
        "false" -> strings.shaderFilterNearest
        else -> strings.shaderUnspecified
    }

    private fun scaleLabel(value: String?): String {
        val scale = value?.toFloatOrNull()?.toInt() ?: return strings.shaderUnspecified
        return if (scale <= 0) strings.shaderUnspecified else strings.shaderScaleX(scale)
    }

    /** True when [itemKey] is the chain's rather than a RetroArch setting's. */
    private fun cyclePipeline(itemKey: String, direction: Int): Boolean {
        when {
            itemKey == SHADER_ENABLED -> {
                host.toggleShader()
                // The chain is seeded from whatever is applied, so it no longer describes what is
                // running. Dropped rather than edited: turning the shader back on restores a whole
                // preset, which is a chain this never built.
                chain = null
                parameterCache = null
                chain()
            }
            itemKey.startsWith(SHADER_PARAM_PREFIX) -> {
                val id = itemKey.removePrefix(SHADER_PARAM_PREFIX)
                val p = parameters().firstOrNull { it.id == id } ?: return true
                val next = (parameterValue(p) + p.step * direction).coerceIn(p.min, p.max)
                editChain(chain().withParameter(id, formatParameter(next)))
            }
            itemKey.startsWith(SHADER_FILTER_PREFIX) -> {
                val i = itemKey.removePrefix(SHADER_FILTER_PREFIX).toIntOrNull() ?: return true
                val order = listOf(null, "true", "false")
                val now = order.indexOf(chain().passes.getOrNull(i)?.settings?.get(KEY_FILTER))
                    .takeIf { it >= 0 } ?: 0
                val next = order[(now + direction + order.size) % order.size]
                editChain(chain().withPassSetting(i, KEY_FILTER, next))
            }
            itemKey.startsWith(SHADER_SCALE_PREFIX) -> {
                val i = itemKey.removePrefix(SHADER_SCALE_PREFIX).toIntOrNull() ?: return true
                val pass = chain().passes.getOrNull(i) ?: return true
                val now = pass.settings[KEY_SCALE_X]?.toFloatOrNull()?.toInt() ?: 0
                val next = (now + direction).coerceIn(0, MAX_SHADER_SCALE)
                // A size means nothing without its type, so both go together or neither does.
                var edited = chain()
                for (key in listOf(KEY_SCALE_TYPE_X, KEY_SCALE_TYPE_Y)) {
                    edited = edited.withPassSetting(i, key, if (next <= 0) null else "source")
                }
                for (key in listOf(KEY_SCALE_X, KEY_SCALE_Y)) {
                    edited = edited.withPassSetting(i, key, if (next <= 0) null else "$next.000000")
                }
                editChain(edited)
            }
            else -> return false
        }
        return true
    }

    // Absent when nothing under it can load, which is the same rule the overlay row follows and
    // also covers the case of the database never having been downloaded.
    private fun MutableList<GenericIgmSettingsItem>.addShaderCategory() {
        if (!host.hasShaders()) return
        add(GenericIgmSettingsItem.Category(
            CuratedCatalog.CATEGORY_SHADER,
            curatedTitle(CuratedCatalog.CATEGORY_SHADER),
        ))
    }

    // Absent rather than empty when the platform has no overlay folders, the same rule the core
    // options and info rows follow. Called from both roots so the two menus cannot drift.
    // Always present: shortcuts are bindable whatever this platform has installed.
    private fun MutableList<GenericIgmSettingsItem>.addInputCategory() {
        add(GenericIgmSettingsItem.Category(
            CuratedCatalog.CATEGORY_INPUT,
            curatedTitle(CuratedCatalog.CATEGORY_INPUT),
        ))
    }

    private fun MutableList<GenericIgmSettingsItem>.addOverlayCategory() {
        if (host.overlays().isEmpty()) return
        add(GenericIgmSettingsItem.Category(
            CuratedCatalog.CATEGORY_OVERLAY,
            curatedTitle(CuratedCatalog.CATEGORY_OVERLAY),
        ))
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
            snapshot(key)
            if (!host.raSetSetting(key, MachineValue(value))) continue
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
            // Ahead of RetroArch's own list rather than buried under it: these are Cannoli's
            // screens, and the list below them is long enough to hide anything at its foot.
            addOverlayCategory()
            addShaderCategory()
            addInputCategory()
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
            s.type == RaSettingType.BOOL && s.machineValue.raw == "true" -> strings.on
            s.type == RaSettingType.BOOL -> strings.off
            else -> s.displayValue
        },
        hint = if (s.requiresRestart) strings.restartHint else null,
        description = s.description,
    )

    override fun cycle(itemKey: String, direction: Int) {
        if (itemKey.startsWith(INFO_ROW_PREFIX)) return
        CuratedCatalog.rowFor(itemKey)?.let { return cycleCurated(it, direction) }
        // Before the RetroArch lookup: this screen never populates currentSettings.
        if (!curated && cyclePipeline(itemKey, direction)) return
        val i = currentSettings.indexOfFirst { it.key == itemKey }
        if (i < 0) return
        val s = currentSettings[i]
        val newValue = RaValueCycler.next(s, direction) ?: return
        if (newValue == s.machineValue) return
        snapshot(s.key)
        if (host.raSetSetting(s.key, newValue)) {
            dirty = true
            changedKeys.add(s.key)
            pending[s.key] = (pending[s.key] ?: 0) + 1
            // The display text for the new value is RetroArch's to produce, so the row shows the
            // machine value until the applied echo brings it back.
            replaceSetting(i, s.copy(
                machineValue = newValue,
                displayValue = s.options?.firstOrNull { it.machine == newValue }?.display
                    ?: newValue.raw,
            ))
        }
    }

    private fun replaceSetting(index: Int, updated: RaSetting) {
        currentSettings = currentSettings.toMutableList().also { it[index] = updated }
        onChanged?.invoke()
    }

    private fun onApplied(key: String) {
        val remaining = (pending[key] ?: 0) - 1
        if (remaining > 0) {
            pending[key] = remaining
            return
        }
        pending.remove(key)
        val fresh = host.raGetSetting(key)
        if (curated && curatedValues.containsKey(key)) {
            curatedValues[key] = fresh?.machineValue?.raw ?: return
            onChanged?.invoke()
            return
        }
        // Not just the key that was written: a change handler moves its neighbours, so setting
        // sub frame shaders zeroes black frame insertion and the swap interval. Re-reading only
        // the written key left those showing what they held before.
        val refreshed = currentSettings.map { row ->
            if (row.key == key) fresh ?: row else host.raGetSetting(row.key) ?: row
        }
        if (refreshed != currentSettings) currentSettings = refreshed
        // RetroArch decides which rows exist from the values, and that list is only re-read on a
        // render, so a row a setting reveals would otherwise arrive one keypress late.
        onChanged?.invoke()
    }

    /** Whether the chain has been edited since it was last compiled. */
    private var chainEdited = false

    /** Staged like any other setting, so leaving the tree offers to keep it. */
    private fun stageChainEdit() {
        chainEdited = true
        markChangedExternally(setOf(SHADER_KEY_CANNOLI))
    }

    override fun applyPendingChanges() {
        if (!chainEdited) return
        chainEdited = false
        host.applyShaderChain()
    }

    // Only pass rows, and only on the chain root.
    override fun canReorder(path: List<String>, index: Int): Boolean =
        passIndexAtRow(path, index) != null

    override fun reorder(path: List<String>, index: Int, delta: Int): Int {
        val from = passIndexAtRow(path, index) ?: return index
        val to = from + delta
        if (to < 0 || to >= chain().passes.size) return index
        editChain(chain().movePass(from, to))
        return index + delta
    }

    // The rows above the passes come and go, so the offset is counted rather than assumed.
    private fun passIndexAtRow(path: List<String>, index: Int): Int? {
        if (curated || path != listOf(CuratedCatalog.CATEGORY_SHADER)) return null
        val key = shaderPipelineRoot().items.getOrNull(index)?.key ?: return null
        if (!key.startsWith(SHADER_PASS_PREFIX)) return null
        return key.removePrefix(SHADER_PASS_PREFIX).toIntOrNull()
    }

    // A pass is the only row with a place in a sequence.
    // Every browser here picks one shader, so all of them end at the chain root.
    override fun returnPathAfter(itemKey: String, path: List<String>): List<String>? =
        if (!curated && itemKey.startsWith(SHADER_PRESET_PREFIX) &&
            path.firstOrNull() == CuratedCatalog.CATEGORY_SHADER
        ) listOf(CuratedCatalog.CATEGORY_SHADER) else null

    override fun canRemoveRow(path: List<String>, index: Int): Boolean =
        passIndexAtRow(path, index) != null

    override fun removeRow(path: List<String>, index: Int) {
        val pass = passIndexAtRow(path, index) ?: return
        editChain(chain().removePass(pass))
    }

    override fun canRestoreDefault(path: List<String>): Boolean =
        path.firstOrNull() == CuratedCatalog.CATEGORY_SHADER && host.shaderOverriddenAtGame()

    override fun restoreDefault(path: List<String>): Set<String> {
        if (!canRestoreDefault(path)) return emptySet()
        val staged = host.restoreShaderDefault()
        markChangedExternally(staged)
        return staged
    }

    override fun activate(itemKey: String): IgmSettingsExit.Prompt? {
        if (itemKey.startsWith(SHADER_PRESET_PREFIX)) {
            activateShaderPreset(itemKey.removePrefix(SHADER_PRESET_PREFIX))
            return null
        }
        return null
    }

    /**
     * Writes the chain as [name] and stages it, so leaving the tree offers to keep it.
     *
     * Saving is what makes a hand-built chain into something that can be chosen again: it is a file
     * in the browser afterwards, indistinguishable from any other preset.
     */
    fun saveShaderPresetAs(name: String) {
        host.applyShaderChain(saveAs = name) ?: return
        // Saving writes and loads it, so nothing is left waiting.
        chainEdited = false
        markChangedExternally(setOf(SHADER_KEY_CANNOLI))
    }

    private fun activateShaderPreset(name: String) {
        // Under a pass, the pick is that pass's shader. Anywhere else it replaces the whole chain.
        val passIndex = currentPath.takeIf { it.getOrNull(2) == SHADER_BROWSE }
            ?.getOrNull(1)?.removePrefix(SHADER_PASS_PREFIX)?.toIntOrNull()
        if (passIndex != null) {
            val preset = host.shaderPresetPath(currentPath.drop(3), name) ?: return
            // The pass keeps its filter and scale; only its shader is being answered.
            val replaced = chain().passes.toMutableList()
            replaced[passIndex] = replaced[passIndex].copy(shader = preset)
            editChain(chain().copy(passes = replaced))
            return
        }
        // Append and prepend combine with the chain instead of replacing it, so they go through
        // RetroArch's own combiner rather than through the picker's apply.
        val combine = currentPath.getOrNull(1)?.takeIf { !curated }
        if (combine == SHADER_APPEND || combine == SHADER_PREPEND) {
            val preset = host.shaderPresetPath(currentPath.drop(2), name) ?: return
            val added = ShaderPreset.parse(java.io.File(preset)) ?: return
            editChain(
                if (combine == SHADER_PREPEND) chain().prepend(added) else chain().append(added)
            )
            return
        }
        // In the chain tree a load replaces the chain without compiling it, so the rows redraw
        // from what was just loaded rather than from what RetroArch had a moment ago. The picker
        // applies instead, because there the list is the whole answer.
        if (!curated && currentPath.getOrNull(1) == SHADER_LOAD) {
            val preset = host.shaderPresetPath(currentPath.drop(2), name) ?: return
            editChain(ShaderPreset.parse(java.io.File(preset)) ?: return)
            return
        }
        // The path this row was listed from is the level the navigator is on, minus the category
        // key that led into the tree. The list stays put: choosing another preset is a press away,
        // which is the point of applying in place.
        markChangedExternally(host.applyShaderPreset(currentPath.drop(1), name))
    }


    // The live preview picker writes its setting straight to RetroArch, so nothing here saw the
    // change. Joining the same changed set is what puts an overlay in the save prompt beside the
    // rows, and is what gives it the per-game scope it would not otherwise have.
    override fun markChangedExternally(keys: Set<String>) {
        if (keys.isEmpty()) return
        // Called before the caller applies its change, which is what makes this the value from
        // before the edit rather than after it.
        keys.forEach(::snapshot)
        changedKeys.addAll(keys)
        dirty = true
        onChanged?.invoke()
    }

    override fun exitPrompt(): IgmSettingsExit =
        if (!dirty) IgmSettingsExit.Close
        else IgmSettingsExit.Prompt(
            title = null,
            options = listOf(
                IgmPromptOption(strings.savePlatform) { saveOverride(RaOverrideScope.SYSTEM) },
                IgmPromptOption(strings.saveGame) { saveOverride(RaOverrideScope.GAME) },
                IgmPromptOption(strings.dontSave) { discardChanges() },
            ),
        )

    private fun saveOverride(scope: RaOverrideScope) {
        val keys = overrideKeys()
        host.raSaveOverride(scope, keys)
        host.saveCannoliOverride(scope, keys)
        clearDirty()
    }

    // Discard puts the running game back as it was. Without this it only dropped the write, so a
    // change stayed live until the next launch recomposed the config from tiers it had never
    // reached, which reads as the button having done nothing.
    private fun discardChanges() {
        restorePriorValues()
        host.revertCannoliOverride()
        clearDirty()
    }

    // The RetroAchievements session keys are injected fresh into the per-launch config every launch
    // and must never be persisted in an override, where a stale copy could re-enable hardcore
    // against a forced softcore. RaOptionCatalog exposes none of them today, so this is defence in
    // depth, dropping any that reached the changed set before it crosses to the native writer.
    private fun overrideKeys(): Set<String> = changedKeys - CheevosSessionKeys.ALL

    private fun snapshot(key: String) {
        if (priorValues.containsKey(key)) return
        val before = if (curated && curatedValues.containsKey(key)) curatedValues[key]?.let(::MachineValue)
        else host.raGetSetting(key)?.machineValue
        before?.let { priorValues[key] = it }
    }

    private fun restorePriorValues() {
        for ((key, value) in priorValues) {
            host.raSetSetting(key, value)
            if (curatedValues.containsKey(key)) curatedValues[key] = value.raw
        }
        onChanged?.invoke()
    }

    private fun clearDirty() {
        dirty = false
        changedKeys.clear()
        priorValues.clear()
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
