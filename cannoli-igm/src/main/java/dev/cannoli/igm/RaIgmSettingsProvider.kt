package dev.cannoli.igm

import dev.cannoli.core.CheevosSessionKeys

import dev.cannoli.core.shader.PresetPass
import dev.cannoli.core.shader.PresetParameter
import dev.cannoli.core.shader.ShaderPragma
import dev.cannoli.core.shader.ShaderPreset

private const val EMULATOR_CATEGORY = "emulator"
private const val INFO_ROW_PREFIX = "info_"

private const val SHADER_LOAD = "load"
private const val SHADER_ENABLED = "enabled"
private const val SHADER_SAVE = "save"
private const val SHADER_APPEND = "append"
private const val SHADER_PREPEND = "prepend"
private const val SHADER_PARAMS = "params"
private const val SHADER_BROWSE = "browse"

private const val KEY_FILTER = "filter_linear"
private const val KEY_SCALE_X = "scale_x"
private const val KEY_SCALE_Y = "scale_y"
private const val KEY_SCALE_TYPE_X = "scale_type_x"
private const val KEY_SCALE_TYPE_Y = "scale_type_y"
private const val SHADER_PASS_PREFIX = "pass_"
private const val SHADER_PARAM_PREFIX = "shader_param:"
private const val SHADER_FILTER_PREFIX = "shader_filter:"
private const val SHADER_SCALE_PREFIX = "shader_scale:"

private val SHADER_KEY_CANNOLI = dev.cannoli.core.config.OverrideTiers.KEY_SHADER

private const val MAX_SHADER_SCALE = 8

internal val HIDDEN_SCREENS = setOf(
    "audio_mixer_settings",
    "menu_sounds",
    "midi_settings",
    "video_fullscreen_mode_settings",
    "video_windowed_mode_settings",
    "video_hdr_settings",
    "updater_settings",
    "wifi_settings",
    "wifi_network_scan",
    "bluetooth_settings",
    "lakka_services",
)

internal val PROMOTED_KEYS = mapOf(
    "video_settings" to listOf("video_shared_context", "video_allow_rotate"),
)

internal val HIDDEN_KEYS = setOf(
    "input_driver",
    "input_joypad_driver",
    "menu_driver",
    "audio_mixer_mute_enable",
    "audio_dsp_plugin",
    "video_filter",
    "filter_enable",
    "video_notch_write_over",
)

class RaIgmSettingsProvider(
    private val host: RaSettingsHost,
    private val strings: RaOptionStrings = RaOptionStrings(),
    private val curated: Boolean = false,
) : IgmSettingsProvider {

    private var onChanged: (() -> Unit)? = null
    private var dirty = false

    private val changedKeys = mutableSetOf<String>()

    private val SHADER_PRESET_PREFIX = "shader_preset:"

    private val priorValues = mutableMapOf<String, MachineValue>()

    private class InFlight(val value: MachineValue, val seq: Int)

    private val inFlight = mutableMapOf<String, InFlight>()
    private var writeSeq = 0

    private var visit = 0

    private var currentSettings: List<RaSetting> = emptyList()

    override fun setOnChanged(callback: () -> Unit) { onChanged = callback }

    private var currentPath: List<String> = emptyList()

    override fun screen(path: List<String>): GenericIgmSettingsScreen {
        currentPath = path
        return screenFor(path)
    }

    private fun screenFor(path: List<String>): GenericIgmSettingsScreen = when {
        path.isEmpty() -> if (curated) curatedRoot() else root()
        path.first() == EMULATOR_CATEGORY -> emulatorScreen(path.getOrNull(1))
        path.first() == CuratedCatalog.CATEGORY_INFO -> infoScreen()
        path.first() == CuratedCatalog.CATEGORY_OVERLAY ->
            GenericIgmSettingsScreen(curatedTitle(CuratedCatalog.CATEGORY_OVERLAY), emptyList())
        path.first() == CuratedCatalog.CATEGORY_INPUT && path.size == 1 ->
            GenericIgmSettingsScreen(
                curatedTitle(CuratedCatalog.CATEGORY_INPUT),
                controllerTypeRows() + GenericIgmSettingsItem.Category(
                    CuratedCatalog.INPUT_BUTTONS,
                    strings.buttonOverrides,
                ) + GenericIgmSettingsItem.Category(
                    CuratedCatalog.INPUT_SHORTCUTS,
                    strings.shortcuts,
                ),
            )
        path.first() == CuratedCatalog.CATEGORY_INPUT ->
            GenericIgmSettingsScreen(
                if (path.getOrNull(1) == CuratedCatalog.INPUT_BUTTONS) strings.buttonOverrides
                else strings.shortcuts,
                emptyList(),
            )
        path.first() == CuratedCatalog.CATEGORY_SHADER ->
            if (curated) shaderScreen(path.drop(1)) else shaderPipelineScreen(path.drop(1))
        curated -> curatedCategoryScreen(path.first())
        else -> raScreen(path.last())
    }

    private fun reachableRows(category: CuratedCatalog.Category): List<CuratedCatalog.Row> =
        category.rows.filter { row -> row.discriminatingKeys.all { host.raGetSetting(it) != null } }

    private fun curatedRoot(): GenericIgmSettingsScreen {
        val items = buildList {
            for (cat in CuratedCatalog.categories) {
                if (reachableRows(cat).isEmpty()) continue
                add(GenericIgmSettingsItem.Category(cat.key, curatedTitle(cat.key)))
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

    private fun resetScopes(): List<RaOverrideScope> =
        listOf(RaOverrideScope.GAME, RaOverrideScope.SYSTEM).filter { host.hasOverrides(it) }

    override fun canReset(path: List<String>): Boolean =
        path.isEmpty() && resetScopes().isNotEmpty()

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

    private fun reset(scope: RaOverrideScope) {
        host.resetOverrides(scope)
        clearDirty()
    }

    private fun shaderScreen(relative: List<String>): GenericIgmSettingsScreen {
        val title = relative.lastOrNull() ?: curatedTitle(CuratedCatalog.CATEGORY_SHADER)
        val applied = host.appliedShaderPreset()
        return GenericIgmSettingsScreen(
            title,
            host.shaderEntries(relative).map { entry ->
                when {
                    entry.isFolder -> GenericIgmSettingsItem.Category(entry.name, entry.name)
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

    private fun shaderPipelineScreen(relative: List<String>): GenericIgmSettingsScreen = when {
        relative.isEmpty() -> shaderPipelineRoot()
        relative.first() == SHADER_LOAD -> shaderScreen(relative.drop(1))
        relative.first() == SHADER_APPEND -> shaderScreen(relative.drop(1))
        relative.first() == SHADER_PREPEND -> shaderScreen(relative.drop(1))
        relative.first() == SHADER_SAVE -> GenericIgmSettingsScreen(strings.shaderSave, emptyList())
        relative.first() == SHADER_PARAMS -> shaderParametersScreen()
        relative.first().startsWith(SHADER_PASS_PREFIX) -> shaderPassScreen(relative)
        else -> GenericIgmSettingsScreen(curatedTitle(CuratedCatalog.CATEGORY_SHADER), emptyList())
    }

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

    private var parameterCache: List<PresetParameter>? = null

    private fun parameters(): List<PresetParameter> =
        parameterCache ?: ShaderPragma.parameters(chain().passes).also { parameterCache = it }

    private fun shaderPipelineRoot(): GenericIgmSettingsScreen {
        val title = curatedTitle(CuratedCatalog.CATEGORY_SHADER)
        val chain = chain()
        val applied = host.appliedShaderPreset() != null
        val toggle = if (applied || host.shaderToRestore() != null) {
            GenericIgmSettingsItem.Choice(
                SHADER_ENABLED,
                strings.shaderEnabled,
                if (applied) strings.on else strings.off,
            )
        } else null

        if (toggle != null && !applied) return GenericIgmSettingsScreen(title, listOf(toggle))

        val items = buildList {
            toggle?.let { add(it) }
            add(GenericIgmSettingsItem.Category(SHADER_LOAD, strings.shaderLoad))
            if (chain.passes.isNotEmpty()) {
                add(GenericIgmSettingsItem.Category(SHADER_PREPEND, strings.shaderAddStart))
                add(GenericIgmSettingsItem.Category(SHADER_APPEND, strings.shaderAddEnd))
                add(GenericIgmSettingsItem.Category(SHADER_SAVE, strings.shaderSave))
            }
            chain.passes.forEachIndexed { i, pass ->
                add(GenericIgmSettingsItem.Category(
                    SHADER_PASS_PREFIX + i,
                    strings.shaderPassNamed(i, passName(pass)),
                ))
            }
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

    private fun parameterValue(p: PresetParameter): Float =
        chain().parameters[p.id]?.toFloatOrNull() ?: p.default

    private fun formatParameter(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString()
        else "%.2f".format(value).trimEnd('0').trimEnd('.')

    private fun shaderPassScreen(relative: List<String>): GenericIgmSettingsScreen {
        val index = relative.first().removePrefix(SHADER_PASS_PREFIX).toIntOrNull()
            ?: return GenericIgmSettingsScreen(strings.shaderPreset, emptyList())
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

    private fun filterLabel(value: String?): String = when (value) {
        "true" -> strings.shaderFilterLinear
        "false" -> strings.shaderFilterNearest
        else -> strings.shaderUnspecified
    }

    private fun scaleLabel(value: String?): String {
        val scale = value?.toFloatOrNull()?.toInt() ?: return strings.shaderUnspecified
        return if (scale <= 0) strings.shaderUnspecified else strings.shaderScaleX(scale)
    }

    private fun cyclePipeline(itemKey: String, direction: Int): Boolean {
        when {
            itemKey == SHADER_ENABLED -> {
                host.toggleShader()
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

    private fun MutableList<GenericIgmSettingsItem>.addShaderCategory() {
        if (!host.hasShaders()) return
        add(GenericIgmSettingsItem.Category(
            CuratedCatalog.CATEGORY_SHADER,
            curatedTitle(CuratedCatalog.CATEGORY_SHADER),
        ))
    }

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

    private fun infoScreen(): GenericIgmSettingsScreen = GenericIgmSettingsScreen(
        curatedTitle(CuratedCatalog.CATEGORY_INFO),
        host.systemInfo().mapIndexed { i, (label, value) ->
            GenericIgmSettingsItem.Choice(key = "$INFO_ROW_PREFIX$i", label = label, value = value, readOnly = true)
        },
    )

    private fun curatedCategoryScreen(categoryKey: String): GenericIgmSettingsScreen {
        if (categoryKey == CuratedCatalog.CATEGORY_INFO) return infoScreen()
        val cat = CuratedCatalog.categories.firstOrNull { it.key == categoryKey }
            ?: return GenericIgmSettingsScreen(curatedTitle(categoryKey), emptyList())
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

    private fun curatedScreenKeys(row: CuratedCatalog.Row): Set<String> =
        CuratedCatalog.categories
            .firstOrNull { cat -> cat.rows.any { it.key == row.key } }
            ?.let { cat -> reachableRows(cat).flatMapTo(mutableSetOf()) { it.settingKeys } }
            ?: row.settingKeys

    private fun valuesFor(row: CuratedCatalog.Row): Map<String, String> {
        val shadow = host.shadowedSettings()
        return row.settingKeys.mapNotNull { key ->
            (shadow[key] ?: read(key)?.machineValue?.raw)?.let { key to it }
        }.toMap()
    }

    private fun cycleCurated(row: CuratedCatalog.Row, direction: Int) {
        val preset = CuratedCatalog.nextPreset(row, valuesFor(row), direction)
        val watch = curatedScreenKeys(row)
        var wrote = false
        for ((key, value) in preset.values) {
            snapshot(key)
            if (!write(key, MachineValue(value), watch, notCollateral = preset.values.keys)) continue
            changedKeys.add(key)
            wrote = true
        }
        if (wrote) {
            dirty = true
            onChanged?.invoke()
        }
    }

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
        loadCoreOptions(wanted)
        val title = cats.firstOrNull { it.categoryKey == categoryKey }?.categoryLabel
            ?: strings.emulator
        return GenericIgmSettingsScreen(title, currentSettings.map(::rowFor))
    }

    private fun loadCoreOptions(refs: List<CoreOptionRef>) {
        currentSettings = refs.mapNotNull { read(it.key)?.let(::withRestartHint) }
    }

    private fun root(): GenericIgmSettingsScreen {
        val items = buildList {
            if (host.coreOptions().isNotEmpty()) {
                add(GenericIgmSettingsItem.Category(
                    EMULATOR_CATEGORY,
                    strings.emulator,
                ))
            }
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

    private val screenTitles = mutableMapOf<String, String>()

    private fun raScreen(label: String): GenericIgmSettingsScreen =
        GenericIgmSettingsScreen(screenTitles[label] ?: label, raRows(label))

    private fun raRows(label: String): List<GenericIgmSettingsItem> {
        val rows = host.raScreenRows(label)
            .filterNot { it.key in HIDDEN_SCREENS || it.key in HIDDEN_KEYS }
        rows.filter { it.isMenu }.forEach { screenTitles[it.key] = it.label }
        val promoted = PROMOTED_KEYS[label].orEmpty()
        loadKeys(rows.filterNot { it.isMenu }.map { it.key } + promoted)
        return rows.mapNotNull { row ->
            if (row.isMenu) GenericIgmSettingsItem.Category(row.key, row.label)
            else currentSettings.firstOrNull { it.key == row.key }?.let(::rowFor)
        } + promoted.mapNotNull { key -> currentSettings.firstOrNull { it.key == key }?.let(::rowFor) }
    }

    private fun loadKeys(keys: List<String>) {
        currentSettings = keys.mapNotNull { key -> read(key)?.let(::withRestartHint) }
    }

    private fun read(key: String): RaSetting? {
        val live = host.raGetSetting(key) ?: return null
        val pending = inFlight[key]?.value ?: return live
        return live.copy(
            machineValue = pending,
            displayValue = live.options?.firstOrNull { it.machine == pending }?.display ?: pending.raw,
        )
    }

    private fun write(
        key: String,
        value: MachineValue,
        watch: Collection<String>,
        notCollateral: Set<String> = emptySet(),
        recordMoved: Boolean = true,
    ): Boolean {
        val seq = ++writeSeq
        val asOf = visit
        inFlight[key] = InFlight(value, seq)
        val queued = host.raApply(key, value, watch) { result ->
            if (inFlight[key]?.seq == seq) inFlight.remove(key)
            if (result != null && recordMoved && asOf == visit) {
                rememberCollateral(result.moved - notCollateral)
            }
            onChanged?.invoke()
        }
        if (!queued && inFlight[key]?.seq == seq) inFlight.remove(key)
        return queued
    }

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
        PortDevices.portFor(itemKey)?.let { return cyclePortDevice(it, direction) }
        CuratedCatalog.rowFor(itemKey)?.let { return cycleCurated(it, direction) }
        // Before the RetroArch lookup: this screen never populates currentSettings.
        if (!curated && cyclePipeline(itemKey, direction)) return
        // The held row says the key is on this screen; the value to step from is read now. Two
        // presses arriving without a render between them would otherwise both step from the same
        // value, and the second would write what the first already did.
        val onRow = currentSettings.firstOrNull { it.key == itemKey } ?: return
        val s = read(onRow.key)?.let(::withRestartHint) ?: return
        val newValue = RaValueCycler.next(s, direction) ?: return
        if (newValue == s.machineValue) return
        snapshot(s.key)
        if (!write(s.key, newValue, currentSettings.map { it.key })) return
        dirty = true
        changedKeys.add(s.key)
        onChanged?.invoke()
    }

    private fun rememberCollateral(moved: Map<String, MachineValue>) {
        for ((key, was) in moved) if (!priorValues.containsKey(key)) priorValues[key] = was
    }

    private fun controllerTypeRows(): List<GenericIgmSettingsItem> {
        val padded = host.players().filter { it.hasPad && it.player < PortDevices.PLAYER_ROWS }.map { it.player }
        val ports = if (padded.size <= 1) listOf(0) else padded
        return ports.mapNotNull { port ->
            val devices = host.portDeviceTypes(port)?.takeIf { it.hasChoice } ?: return@mapNotNull null
            GenericIgmSettingsItem.Choice(
                key = PortDevices.keyFor(port),
                label = if (ports.size == 1) strings.controllerType else strings.playerController(port + 1),
                value = devices.labelFor(devices.current),
            )
        }
    }

    private fun cyclePortDevice(port: Int, direction: Int) {
        val devices = host.portDeviceTypes(port)?.takeIf { it.hasChoice } ?: return
        val choices = devices.choices
        val at = choices.indexOfFirst { it.id == devices.current }.coerceAtLeast(0)
        val next = choices[Math.floorMod(at + direction, choices.size)].id
        if (next == devices.current) return
        val key = PortDevices.keyFor(port)
        if (!priorValues.containsKey(key)) priorValues[key] = MachineValue(devices.current.toString())
        host.setPortDevice(port, next)
        dirty = true
        changedKeys.add(key)
        onChanged?.invoke()
    }

    private var chainEdited = false

    private fun stageChainEdit() {
        chainEdited = true
        markChangedExternally(setOf(SHADER_KEY_CANNOLI))
    }

    override fun applyPendingChanges() {
        if (!chainEdited) return
        chainEdited = false
        host.applyShaderChain()
    }

    override fun canReorder(path: List<String>, index: Int): Boolean =
        passIndexAtRow(path, index) != null

    override fun reorder(path: List<String>, index: Int, delta: Int): Int {
        val from = passIndexAtRow(path, index) ?: return index
        val to = from + delta
        if (to < 0 || to >= chain().passes.size) return index
        editChain(chain().movePass(from, to))
        return index + delta
    }

    private fun passIndexAtRow(path: List<String>, index: Int): Int? {
        if (curated || path != listOf(CuratedCatalog.CATEGORY_SHADER)) return null
        val key = shaderPipelineRoot().items.getOrNull(index)?.key ?: return null
        if (!key.startsWith(SHADER_PASS_PREFIX)) return null
        return key.removePrefix(SHADER_PASS_PREFIX).toIntOrNull()
    }

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

    fun saveShaderPresetAs(name: String) {
        host.applyShaderChain(saveAs = name) ?: return
        chainEdited = false
        markChangedExternally(setOf(SHADER_KEY_CANNOLI))
    }

    private fun activateShaderPreset(name: String) {
        val passIndex = currentPath.takeIf { it.getOrNull(2) == SHADER_BROWSE }
            ?.getOrNull(1)?.removePrefix(SHADER_PASS_PREFIX)?.toIntOrNull()
        if (passIndex != null) {
            val preset = host.shaderPresetPath(currentPath.drop(3), name) ?: return
            val replaced = chain().passes.toMutableList()
            replaced[passIndex] = replaced[passIndex].copy(shader = preset)
            editChain(chain().copy(passes = replaced))
            return
        }
        val combine = currentPath.getOrNull(1)?.takeIf { !curated }
        if (combine == SHADER_APPEND || combine == SHADER_PREPEND) {
            val preset = host.shaderPresetPath(currentPath.drop(2), name) ?: return
            val added = ShaderPreset.parse(java.io.File(preset)) ?: return
            editChain(
                if (combine == SHADER_PREPEND) chain().prepend(added) else chain().append(added)
            )
            return
        }
        if (!curated && currentPath.getOrNull(1) == SHADER_LOAD) {
            val preset = host.shaderPresetPath(currentPath.drop(2), name) ?: return
            editChain(ShaderPreset.parse(java.io.File(preset)) ?: return)
            return
        }
        markChangedExternally(host.applyShaderPreset(currentPath.drop(1), name))
    }


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
        val before = host.shadowedSettings()[key]?.let(::MachineValue)
            ?: host.raGetSetting(key)?.machineValue
        before?.let { priorValues[key] = it }
    }

    private fun restorePriorValues() {
        for ((key, value) in priorValues) {
            val port = PortDevices.portFor(key)
            if (port != null) {
                value.raw.toIntOrNull()?.let { host.setPortDevice(port, it) }
                continue
            }
            write(key, value, emptyList(), recordMoved = false)
        }
        onChanged?.invoke()
    }

    private fun clearDirty() {
        visit++
        dirty = false
        changedKeys.clear()
        priorValues.clear()
    }
}

private val RESTART_SUFFIX = Regex("""[\s\-–—]*\(\s*(?:restart|reboot)\s*\)\s*$""", RegexOption.IGNORE_CASE)

internal fun withRestartHint(s: RaSetting): RaSetting =
    if (RESTART_SUFFIX.containsMatchIn(s.label)) {
        s.copy(label = s.label.replace(RESTART_SUFFIX, ""), requiresRestart = true)
    } else {
        s
    }
