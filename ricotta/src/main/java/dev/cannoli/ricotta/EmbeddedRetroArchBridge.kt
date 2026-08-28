package dev.cannoli.ricotta

import android.os.Handler
import android.os.Looper
import dev.cannoli.core.StateSlotPaths
import dev.cannoli.core.config.OverrideTiers
import dev.cannoli.core.config.RetroArchConfigComposer
import dev.cannoli.core.config.TierValue
import dev.cannoli.core.overlay.OverlayCatalog
import dev.cannoli.core.shader.ShaderCatalog
import dev.cannoli.core.shader.ShaderEntry
import dev.cannoli.core.shader.ShaderIndex
import dev.cannoli.core.shader.ShaderPreset
import java.io.File
import dev.cannoli.igm.AchievementInfo
import dev.cannoli.igm.RetroArchBridge
import dev.cannoli.igm.RaOverrideScope
import dev.cannoli.igm.MachineValue
import dev.cannoli.igm.RaOption
import dev.cannoli.igm.RaSetting
import dev.cannoli.igm.RaSettingType
import dev.cannoli.igm.RaScreenRow
import dev.cannoli.igm.RaSettingsHost

class EmbeddedRetroArchBridge(
    private val stateBasePath: String,
    private val hardcoreInEffect: Boolean,
    private val cannoliRoot: String,
    private val platformTag: String,
    private val romBaseName: String,
    coreId: String,
) : RetroArchBridge, RaSettingsHost {

    override val supportsAchievements = true

    private var onMenuClosedCallback: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        nativeInit()
        // The IGM's "Save for game/platform" rows write to Cannoli's own override tiers, which are
        // keyed by these. Set before the IGM is interactive so no save can precede it.
        nativeSetCannoliContext(cannoliRoot, platformTag, romBaseName, coreId)
        applyStoredShader()
    }

    fun destroy() {
        nativeDestroy()
    }

    /**
     * Called from C via JNI when RetroArch's menu closes.
     * Posts the callback to the main thread.
     */
    @Suppress("unused")
    fun onNativeMenuClosed() {
        mainHandler.post {
            onMenuClosedCallback?.invoke()
        }
    }

    /** Debug: called from C for every key down event to show keycode via Toast */
    var onDebugKey: ((Int) -> Unit)? = null

    @Suppress("unused")
    fun onDebugKey(keycode: Int) {
        mainHandler.post {
            onDebugKey?.invoke(keycode)
        }
    }

    var onIgmTrigger: (() -> Unit)? = null

    @Suppress("unused")
    fun onIgmTrigger() {
        mainHandler.post {
            onIgmTrigger?.invoke()
        }
    }

    // Structured OSD event from a RetroArch source site Cannoli owns.
    // type: 0 save, 1 load, 4 undo-save. slot: RetroArch state_slot (< 0 = auto).
    var onOsdEvent: ((Int, Int) -> Unit)? = null

    @Suppress("unused")
    fun onOsdEvent(type: Int, slot: Int) {
        mainHandler.post {
            onOsdEvent?.invoke(type, slot)
        }
    }

    var onOsdAchievement: ((String) -> Unit)? = null

    @Suppress("unused")
    fun onOsdAchievement(title: String) {
        mainHandler.post {
            onOsdAchievement?.invoke(title)
        }
    }

    fun setIgmTriggerKeycodes(keycodes: IntArray) = nativeSetIgmTriggerKeycodes(keycodes)

    fun setBuiltinPorts(ports: IntArray) = nativeSetBuiltinPorts(ports)

    fun setIGMVisible(visible: Boolean) {
        nativeSetIGMVisible(visible)
    }

    // EmulatorBridge implementation

    override fun reset() = nativeReset()

    override fun quit() = nativeQuit()

    fun pause() = nativePause()
    fun unpause() = nativeUnpause()

    override val savesOnQuit: Boolean
        get() = raGetSetting("savestate_auto_save")?.machineValue?.raw == "true"

    // Carried from the launcher's authoritative effective-hardcore decision across the launch
    // parcel, not read from the live cheevos settings. A stale per-game RetroArch override can layer
    // hardcore=true back over the launch config, so the live setting is not a trustworthy gate; the
    // launcher already accounts for global hardcore and per-game force-softcore. Immutable, so the
    // rows never come or go part-way through a game.
    override val savestatesAllowed: Boolean = savestatesAllowedFor(hardcoreInEffect)

    // IGM slot index (0 = auto, 1..10 = manual) maps to RetroArch's state_slot:
    // auto -> -1, "Slot 0" (index 1) -> 0, "Slot N" -> N-1. StateSlotPaths owns
    // this convention so the bridge and SaveSlotStore agree.
    override fun saveState(slot: Int) = nativeSaveState(StateSlotPaths.retroArchStateSlot(slot))
    override fun loadState(slot: Int) = nativeLoadState(StateSlotPaths.retroArchStateSlot(slot))

    fun undoSaveState() = nativeUndoSaveState()
    fun undoLoadState() = nativeUndoLoadState()

    override fun getAchievements(): List<AchievementInfo> =
        decodeAchievements(nativeGetAchievementData())

    override fun getDiskCount() = nativeDiskCount()
    override fun getDiskIndex() = nativeDiskIndex()
    override fun setDiskIndex(index: Int) = nativeSetDiskIndex(index)

    var raStrings: dev.cannoli.igm.RaOptionStrings = dev.cannoli.igm.RaOptionStrings()
    var onOpenNativeMenu: (() -> Unit)? = null
    var curatedSettings: Boolean = true

    // Points at ViewportController.shadowedSettings once the host wires it up. Left as a no-op
    // provider until then, so raGetSetting-backed reads are the only source before the controller
    // exists.
    var shadowedSettingsProvider: () -> Map<String, String> = { emptyMap() }
    override fun shadowedSettings(): Map<String, String> = shadowedSettingsProvider()

    override fun settingsProvider(): dev.cannoli.igm.IgmSettingsProvider {
        // A fresh provider per open is what resets the dirty state, so this is the moment the
        // settings tree begins and the right place to latch what Discard should return to.
        latchShaderForEdit()
        return dev.cannoli.igm.RaIgmSettingsProvider(
            host = this,
            strings = raStrings,
            curated = curatedSettings,
        )
    }

    override fun coreOptions(): List<dev.cannoli.igm.CoreOptionRef> =
        nativeCoreOptionKeys()?.map { entry ->
            val parts = entry.split('|', limit = 3)
            dev.cannoli.igm.CoreOptionRef(
                key = parts[0],
                categoryKey = parts.getOrNull(1).orEmpty(),
                categoryLabel = parts.getOrNull(2).orEmpty(),
            )
        } ?: emptyList()

    override fun systemInfo(): List<Pair<String, String>> {
        val arr = nativeSystemInfo() ?: return emptyList()
        return buildList {
            arr.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { add(raStrings.infoCore to it) }
            arr.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { add(raStrings.infoCoreVersion to it) }
        }
    }

    // Listing is what folders a loose image left by v1, so it happens here rather than at launch.
    private var shaderPresence: Boolean? = null

    /**
     * Answered once per session from a single listing, with no recursion.
     *
     * The settings root asks this every time it is composed. Walking the tree to answer it made the
     * whole menu crawl: the database is thousands of files across hundreds of folders, and proving
     * a preset exists somewhere under them meant statting most of it, on a card where that is slow.
     * A file the driver can load at the top, or any folder at all, is enough to offer the row; the
     * browser itself is what finds out whether a particular folder leads anywhere.
     */
    override fun hasShaders(): Boolean = shaderPresence ?: run {
        val present = if (cannoliRoot.isEmpty()) false else {
            val ext = ShaderCatalog.presetExtension(videoDriver())
            ShaderCatalog.shadersDir(File(cannoliRoot)).listFiles()?.any { child ->
                child.isDirectory || child.extension.equals(ext, ignoreCase = true)
            } ?: false
        }
        shaderPresence = present
        present
    }

    /**
     * The database is thousands of files, so a level is read on demand rather than cached: the
     * browser asks for one folder at a time and each is small, where holding the whole tree would
     * cost far more than the listing it saves.
     */
    /**
     * A path is real directories followed by at most one family name.
     *
     * A family is a group of presets sharing a name stem, not a folder on disk, so descending into
     * one leaves the filesystem behind. Each family row carries its full stem, so the last segment
     * that is not a directory is the family and everything before it is the path to read.
     */
    override fun shaderEntries(path: List<String>): List<ShaderEntry> {
        if (cannoliRoot.isEmpty()) return emptyList()
        val root = ShaderCatalog.shadersDir(File(cannoliRoot))
        return ShaderCatalog.list(root, path, videoDriver(), shaderIndex(root))
    }

    override fun applyShaderPreset(path: List<String>, name: String): Set<String> {
        val preset = shaderPresetPath(path, name) ?: return emptySet()
        // See pickOverlay: choosing again is asking to override again.
        cleared.remove(KEY_SHADER)
        // The config value alone loads nothing: the native call is what compiles the preset into
        // the render chain. The setting is written too, so the tier persists what is running.
        nativeSetShaderPreset(preset)
        // Only the enable flag is RetroArch's. There is no video_shader config key: RetroArch keeps
        // the loaded preset in a runtime path and finds it again by looking for an auto-shader named
        // after the content, so a chosen preset has nowhere in its config to live. Cannoli stores
        // the path in its own tier instead, the way it already does for a bezel.
        raSetSetting(SHADER_ENABLE_KEY, MachineValue("true"))
        appliedShader = preset
        return setOf(KEY_SHADER, SHADER_ENABLE_KEY)
    }

    private var appliedShader: String? = null

    /** Absolute path of the preset in force, so the browser can mark the row that is applied. */
    /**
     * Null once the chain stops matching the preset it came from, so the picker marks nothing
     * rather than a preset that is no longer what is running. Not cleared by compiling: that runs
     * on the way out of the tree, before the save prompt, and clearing it there threw away the path
     * a load had recorded and left the save writing an empty value, which means an explicit off.
     */
    override fun appliedShaderPreset(): String? = appliedShader

    private var loadedIndex: ShaderIndex.Index? = null
    private var indexLoaded = false

    // Read once and kept: it is a few thousand short lines, and the browser asks for it on every
    // level. A missing one is remembered as missing rather than retried per render.
    private fun shaderIndex(dir: File): ShaderIndex.Index? {
        if (!indexLoaded) {
            loadedIndex = ShaderIndex.load(dir)
            indexLoaded = true
        }
        return loadedIndex
    }

    override fun shaderPresetPath(path: List<String>, name: String): String? {
        if (cannoliRoot.isEmpty()) return null
        val root = ShaderCatalog.shadersDir(File(cannoliRoot))
        return ShaderCatalog.presetFile(root, path, name, videoDriver())
            .takeIf { it.isFile }
            ?.absolutePath
    }

    // What RetroArch is running right now, not what a config asked for: a hardware-rendered core
    // overrides the driver at load, and a preset the running driver cannot parse simply fails.
    private fun videoDriver(): String = raGetSetting("video_driver")?.machineValue?.raw.orEmpty()

    private var overlayNames: List<String>? = null

    /**
     * Cached because the settings root asks for this every time it is composed, and the scan walks
     * the card and may move files: v1's loose images are foldered here. SD listings are slow and
     * never cache themselves, so doing it per render put that on the menu's critical path.
     * [rescanOverlays] is the way to see a folder added mid-session.
     */
    override fun overlays(): List<String> = overlayNames ?: rescanOverlays()

    fun rescanOverlays(): List<String> {
        val names =
            if (cannoliRoot.isEmpty()) emptyList()
            else OverlayCatalog.list(OverlayCatalog.platformDir(File(cannoliRoot), platformTag))
        overlayNames = names
        return names
    }

    override fun raGetSetting(key: String): RaSetting? {
        val fields = nativeRaGetSetting(key)?.asFields() ?: return null
        val machine = fields["machine"] ?: return null
        val type = when (fields["type"]) {
            "BOOL" -> RaSettingType.BOOL
            "INT" -> RaSettingType.INT
            "FLOAT" -> RaSettingType.FLOAT
            "ENUM" -> RaSettingType.ENUM
            "STRING_RO" -> RaSettingType.STRING_RO
            else -> return null
        }
        return RaSetting(
            key = key,
            label = fields["label"].orEmpty(),
            type = type,
            machineValue = MachineValue(machine),
            displayValue = fields["display"] ?: machine,
            min = fields["min"]?.toFloatOrNull(),
            max = fields["max"]?.toFloatOrNull(),
            step = fields["step"]?.toFloatOrNull(),
            options = fields.options(),
            requiresRestart = fields["restart"] == "1",
            description = fields["desc"]?.takeIf { it.isNotEmpty() },
        )
    }

    /** Alternating name and value, so a field can be added without shifting another. */
    private fun Array<String>.asFields(): Map<String, String> =
        toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }

    private fun Map<String, String>.options(): List<RaOption>? {
        val found = generateSequence(0) { it + 1 }
            .map { this["opt$it.machine"] to this["opt$it.display"] }
            .takeWhile { (machine, _) -> machine != null }
            .map { (machine, display) -> RaOption(MachineValue(machine!!), display ?: machine) }
            .toList()
        return found.takeIf { it.isNotEmpty() }
    }

    // False means the key resolves to nothing, so the write was never queued. The apply itself is
    // asynchronous and its outcome arrives later through the applied echo.
    override fun raSetSetting(key: String, value: MachineValue): Boolean =
        nativeRaSetSetting(key, value.raw)

    override fun coreGeometry(): IntArray? = nativeCoreGeometry()

    override fun applyViewport(x: Int, y: Int, w: Int, h: Int): Boolean =
        nativeApplyViewport(x, y, w, h)

    override fun clearViewport(restoreAspectIdx: Int, restoreIntegerScale: Boolean): Boolean =
        nativeClearViewport(restoreAspectIdx, restoreIntegerScale)

    override fun raAspectIndex(): Int = nativeRaAspectIndex()

    override fun raIntegerScale(): Boolean = nativeRaIntegerScale()

    override fun raAspectValue(): Float = nativeRaAspectValue()

    // RetroArch decides which rows a settings screen has right now, in what order, under what name,
    // and which of them lead somewhere. Values are not read here: both menu modes go through
    // raGetSetting so there stays one read path, one write path and one changed-key set.
    override fun raScreenRows(label: String): List<RaScreenRow> =
        nativeRaScreenRows(label).orEmpty().mapNotNull { encoded ->
            val f = encoded.split('\u001f')
            if (f.size < 3 || f[0].isEmpty()) null
            else RaScreenRow(key = f[0], label = f[1], isMenu = f[2] == "1")
        }

    // Spelled out rather than taken from the enum ordinal, so reordering the enum cannot silently
    // retarget where an override lands. ricotta_bridge.c decodes it the same way.
    /**
     * The overlay a person picked, by folder name, stored in the core-independent tier because a
     * bezel is the same choice whichever core runs the platform. Cannoli draws it, so this is not a
     * RetroArch setting and never reaches RetroArch's config.
     */
    var cannoliOverlayName: String? = null

    /** Set by the host, which owns what is drawn: the bridge only knows how to store the name. */
    var onCannoliSaved: (() -> Unit)? = null
    var onCannoliRevert: (() -> Unit)? = null

    override fun revertCannoliOverride() {
        // The shader in force when the tree was entered, reloaded rather than merely rewritten.
        // Without this a discarded audition stays on screen: nothing was saved, but what you are
        // looking at is the last preset you tried.
        shaderBeforeEdit.let { before ->
            if (before != appliedShader) {
                nativeSetShaderPreset(before.orEmpty())
                appliedShader = before
            }
        }
        cleared.clear()
        onCannoliRevert?.invoke()
    }

    /** What was loaded when the settings tree was entered, so Discard has something to go back to. */
    private var shaderBeforeEdit: String? = null

    fun latchShaderForEdit() {
        shaderBeforeEdit = appliedShaderPreset()
    }

    override fun saveCannoliOverride(scope: RaOverrideScope, changed: Set<String>) {
        // Only the keys this visit actually moved. Otherwise saving any setting at platform scope
        // would copy a game's bezel, or its shader, onto the whole platform.
        val mine = CANNOLI_KEYS.filter { it in changed }
        if (mine.isEmpty() && cleared.isEmpty()) return
        if (cannoliRoot.isEmpty()) return
        val target = tierFile(scope) ?: return

        val values = LinkedHashMap<String, TierValue>()
        for (key in mine) {
            if (key in cleared) continue
            when (key) {
                KEY_OVERLAY -> values[key] =
                    cannoliOverlayName?.let { TierValue.Set(it) } ?: TierValue.Off
                KEY_SHADER -> shaderTierValue(scope)?.let { values[key] = it }
            }
        }
        writeTier(target, values)

        // Dropping the game's override and saving a value are independent answers to different
        // questions, so both are honoured: asking a game to stop overriding stays true even when
        // the same visit saves what is now showing onto the platform.
        if (cleared.isNotEmpty()) {
            gameTier()?.let { writeTier(it, cleared.associateWith { TierValue.Inherit }) }
        }
        cleared.clear()
        onCannoliSaved?.invoke()
    }

    /** Null leaves the key as it is, for a write that failed and so settled nothing. */
    private fun shaderTierValue(scope: RaOverrideScope): TierValue? {
        val chain = pendingChain
            ?: return appliedShader?.let { TierValue.Set(it) } ?: TierValue.Off
        if (chain.passes.isEmpty()) {
            applyShaderChain()
            return TierValue.Off
        }
        return applyShaderChain(autoPresetName(scope))?.let { TierValue.Set(it) }
    }

    /**
     * Keys this game has been asked to stop overriding, cleared from its tier when the visit saves.
     *
     * Written as a removal rather than an empty value, because the two mean different things here:
     * empty is an explicit off that masks the platform, and absent is the game having no opinion.
     * Without the second there is no way back once a game has been switched off, which is the
     * direction the explicit off broke.
     */
    private val cleared = mutableSetOf<String>()

    /**
     * Whether this game overrides [key] at all, which is the only case worth offering to undo.
     *
     * A clear staged this visit already counts as not overriding, even though the file still says
     * otherwise until the save. Reading the file alone would keep offering an undo for something
     * already undone.
     */
    fun overridesAtGame(key: String): Boolean =
        key !in cleared && gameTier()?.let { tierValue(it, key) } !is TierValue.Inherit?

    /** Null when the menu is not editing one, so a save leaves the stored preset alone. */
    private var pendingChain: ShaderPreset? = null

    override fun setShaderChain(chain: ShaderPreset?) {
        pendingChain = chain
    }

    override fun applyShaderChain(saveAs: String?): String? {
        val chain = pendingChain ?: return null
        if (cannoliRoot.isEmpty()) return null
        val shaders = ShaderCatalog.shadersDir(File(cannoliRoot))
        val ext = ShaderCatalog.presetExtension(videoDriver())
        // An empty path clears the shader.
        if (chain.passes.isEmpty()) {
            nativeSetShaderPreset("")
            appliedShader = null
            return null
        }
        val target = if (saveAs == null) File(shaders, "$WORKING_CHAIN.$ext")
        else File(shaders, ShaderCatalog.CUSTOM_DIR).apply { mkdirs() }.let { File(it, "$saveAs.$ext") }
        return try {
            target.parentFile?.mkdirs()
            target.writeText(chain.serialise())
            nativeSetShaderPreset(target.absolutePath)
            appliedShader = target.absolutePath
            target.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    override fun shaderOverriddenAtGame(): Boolean = overridesAtGame(KEY_SHADER)

    override fun restoreShaderDefault(): Set<String> {
        val inherited = clearGameOverride(KEY_SHADER)
        // Loaded, not merely recorded: applying is the only thing that installs a preset, and an
        // empty path is what clears the chain when the platform has nothing to fall back to.
        nativeSetShaderPreset(inherited.orEmpty())
        appliedShader = inherited
        return setOf(KEY_SHADER)
    }

    /** Drops this game's override of [key], returning what it will inherit instead. */
    private fun clearGameOverride(key: String): String? {
        cleared.add(key)
        return systemTier()?.let { tierValue(it, key) }?.chosen
    }

    /**
     * Chooses a bezel, cancelling any staged clear.
     *
     * Picking after dropping the override is asking to override again, so the two cannot both stand:
     * leaving the clear staged would throw the pick away at save time and look like the choice never
     * took.
     */
    fun pickOverlay(name: String?) {
        cleared.remove(KEY_OVERLAY)
        cannoliOverlayName = name
    }

    /** Drops this game's bezel override, returning the platform's for the host to draw. */
    fun restoreOverlayDefault(): String? {
        val inherited = clearGameOverride(KEY_OVERLAY)
        cannoliOverlayName = inherited
        return inherited
    }

    // Merged rather than rewritten: the tier is shared with anything else core-independent, and the
    // overlay and the shader land in the same file.
    private fun writeTier(file: File, values: Map<String, TierValue>) {
        if (values.isEmpty()) return
        editTier(file) { merged ->
            for ((key, value) in values) {
                val text = TierValue.serialise(value)
                if (text == null) merged.remove(key) else merged[key] = text
            }
        }
    }

    private fun editTier(file: File, edit: (LinkedHashMap<String, String>) -> Unit) {
        val merged = LinkedHashMap(readTier(file))
        edit(merged)
        try {
            file.parentFile?.mkdirs()
            file.writeText(merged.entries.joinToString("\n") { "${it.key} = \"${it.value}\"" } + "\n")
        } catch (_: Exception) {
        }
    }

    private fun readTier(file: File): Map<String, String> = try {
        if (file.isFile) RetroArchConfigComposer.parse(file.readText()) else emptyMap()
    } catch (_: Exception) { emptyMap() }

    private fun tierValue(file: File, key: String): TierValue = TierValue.of(readTier(file)[key])

    /**
     * What an automatically saved chain is called: the thing it was saved for.
     *
     * Flat rather than nested, so these list beside the presets someone named themselves instead of
     * being buried a folder deep.
     */
    private fun autoPresetName(scope: RaOverrideScope): String {
        val name = when (scope) {
            RaOverrideScope.GAME -> "$platformTag - $romBaseName"
            RaOverrideScope.SYSTEM -> platformTag
        }
        return name.filterNot { it in FILENAME_RESERVED }.trim().ifEmpty { "chain" }
    }

    private fun gameTier(): File? {
        if (cannoliRoot.isEmpty() || romBaseName.isEmpty()) return null
        val platform = File(File(File(cannoliRoot), OverrideTiers.GAMES_DIR), platformTag)
        return File(File(platform, romBaseName), "${OverrideTiers.SHARED}.cfg")
    }

    private fun systemTier(): File? {
        if (cannoliRoot.isEmpty()) return null
        val platform = File(File(File(cannoliRoot), OverrideTiers.SYSTEMS_DIR), platformTag)
        return File(platform, "${OverrideTiers.SHARED}.cfg")
    }

    private fun tierFile(scope: RaOverrideScope): File? = when (scope) {
        RaOverrideScope.GAME -> gameTier()
        RaOverrideScope.SYSTEM -> systemTier()
    }

    /** The stored overlay for this game, game scope winning, or null when none is set. */
    fun storedOverlayName(): String? = storedTierValue(KEY_OVERLAY).chosen

    /**
     * Loads the shader this game was saved with, because nothing else will.
     *
     * Applying is what installs a preset into the render chain, and only the menu ever applies one,
     * so a saved choice sits in the tier doing nothing until someone opens the menu and picks it
     * again. Queued rather than called: it lands on the runloop once video is up.
     */
    private fun applyStoredShader() {
        val stored = storedTierValue(KEY_SHADER).chosen ?: return
        // A preset that has been deleted since, or a card that moved: applying it would clear the
        // chain to nothing, which looks like the shader having been forgotten rather than missing.
        if (!File(stored).isFile) return
        nativeSetShaderPreset(stored)
        appliedShader = stored
    }

    /**
     * What the nearest tier says about [key], game scope winning.
     *
     * The nearest tier that mentions the key wins, even when what it says is empty. A tier that does
     * not mention it at all is silent, and the next one out is asked instead.
     */
    private fun storedTierValue(key: String): TierValue =
        listOfNotNull(gameTier(), systemTier())
            .map { tierValue(it, key) }
            .firstOrNull { it !is TierValue.Inherit }
            ?: TierValue.Inherit

    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {
        val encoded = if (scope == RaOverrideScope.GAME) 1 else 0
        nativeRaSaveOverride(encoded, encodeOverrideKeys(keys))
    }

    private var onRaAppliedCallback: ((String, String) -> Unit)? = null

    override fun setOnRaSettingApplied(callback: (key: String, value: String) -> Unit) {
        onRaAppliedCallback = callback
    }

    /**
     * A second listener for the same echo, owned by this process rather than by the in-game menu.
     * setOnRaSettingApplied is a single slot the IGM's settings provider claims, and the viewport
     * needs the same signal for a different reason, so it gets its own rather than the two
     * contending for one.
     */
    private var onRaAppliedLocal: ((String, String) -> Unit)? = null

    fun setOnRaSettingAppliedLocal(callback: ((key: String, value: String) -> Unit)?) {
        onRaAppliedLocal = callback
    }

    @Suppress("unused")
    fun onRaSettingApplied(key: String, value: String) {
        mainHandler.post {
            onRaAppliedCallback?.invoke(key, value)
            onRaAppliedLocal?.invoke(key, value)
        }
    }

    private var onCheatsLoadedCallback: ((List<RetroArchBridge.CheatRow>) -> Unit)? = null

    override fun setOnCheatsLoaded(callback: (List<RetroArchBridge.CheatRow>) -> Unit) {
        onCheatsLoadedCallback = callback
    }

    @Suppress("unused")
    fun onCheatsLoaded(payload: String) {
        val rows = decodeCheatRows(payload)
        mainHandler.post { onCheatsLoadedCallback?.invoke(rows) }
    }

    override fun loadCheatFile(path: String) = nativeCheatLoadFile(path)
    override fun toggleCheat(index: Int) = nativeCheatToggle(index)
    override fun applyCheats() = nativeCheatApply()

    override val hardcoreActive: Boolean
        get() = nativeCheatHardcoreActive()

    override fun openNativeMenu() = nativeMenuToggle()

    override fun setOnNativeMenuClosed(callback: () -> Unit) {
        onMenuClosedCallback = callback
    }

    // Native methods
    private external fun nativeInit()
    private external fun nativeSetCannoliContext(root: String, tag: String, base: String, core: String)
    private external fun nativeRaScreenRows(label: String): Array<String>?
    private external fun nativeDestroy()
    private external fun nativeSaveState(slot: Int)
    private external fun nativeLoadState(slot: Int)
    private external fun nativeUndoSaveState()
    private external fun nativeUndoLoadState()
    private external fun nativeReset()
    private external fun nativeQuit()
    private external fun nativePause()
    private external fun nativeUnpause()
    private external fun nativeIsPaused(): Boolean
    private external fun nativeMenuToggle()
    private external fun nativeCoreOptionKeys(): Array<String>?
    private external fun nativeSystemInfo(): Array<String>?
    private external fun nativeDiskCount(): Int
    private external fun nativeDiskIndex(): Int
    private external fun nativeDiskLabel(index: Int): String?
    private external fun nativeSetDiskIndex(index: Int)
    private external fun nativeSetIGMVisible(visible: Boolean)
    private external fun nativeSetIgmTriggerKeycodes(keycodes: IntArray)

    private external fun nativeSetBuiltinPorts(ports: IntArray)
    private external fun nativeGetAchievementData(): String
    private external fun nativeCheatLoadFile(path: String)
    private external fun nativeCheatToggle(index: Int)
    private external fun nativeCheatApply()
    private external fun nativeCheatHardcoreActive(): Boolean
    private external fun nativeRaGetSetting(key: String): Array<String>?
    private external fun nativeRaSetSetting(key: String, value: String): Boolean
    private external fun nativeSetShaderPreset(path: String)

    private external fun nativeRaSaveOverride(scope: Int, keys: String)
    private external fun nativeCoreGeometry(): IntArray?
    private external fun nativeApplyViewport(x: Int, y: Int, w: Int, h: Int): Boolean
    private external fun nativeClearViewport(restoreAspectIdx: Int, restoreIntegerScale: Boolean): Boolean
    private external fun nativeRaAspectIndex(): Int
    private external fun nativeRaAspectValue(): Float
    private external fun nativeRaIntegerScale(): Boolean

    companion object {
        /**
         * Not a RetroArch setting, so its native writer skips it. Staging it is purely what marks
         * the settings tree dirty, so the save prompt appears and offers platform or game.
         */
        /** Mirrors RICOTTA_RA_SETTING_FIELDS. Both describers allocate this many. */
        private const val RA_SETTING_FIELDS = 10


        /**
         * Cannoli's own, for the same reason as the overlay: RetroArch has no config key naming the
         * preset in force, so there is nothing of its own to write into.
         */

        /** Characters a filename cannot carry, dropped from a name taken off a tag or a rom. */
        private val FILENAME_RESERVED = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /**
         * Where a chain lives while it is only being looked at. Hidden, so the browser does not
         * offer the working copy of the thing you are currently editing as something to load.
         */
        private const val WORKING_CHAIN = ".cannoli_chain"

        const val KEY_OVERLAY = OverrideTiers.KEY_OVERLAY
        const val KEY_SHADER = OverrideTiers.KEY_SHADER

        private val CANNOLI_KEYS = listOf(KEY_OVERLAY, KEY_SHADER)
        // Real RetroArch settings: a shader is a pass in its render chain, so unlike the bezel
        // this genuinely belongs to RetroArch.
        private const val SHADER_ENABLE_KEY = "video_shader_enable"
        // The save state rows go only when this launch is really in hardcore. The launcher decides
        // that once (LaunchManager.hardcoreInEffect, folding in global hardcore and per-game
        // force-softcore) and carries it across the parcel, so the gate agrees with the launcher's
        // resume and save-on-quit gating by construction instead of re-deriving it from live
        // settings a stale per-game override can clobber.
        internal fun savestatesAllowedFor(hardcoreInEffect: Boolean): Boolean = !hardcoreInEffect

        // RA setting names are safe ASCII with no newlines, so the changed-key set crosses JNI as
        // a plain newline-delimited list that ricotta_ra_save_override splits on '\n'. An empty set
        // encodes to "", which the native side treats as nothing to save.
        internal fun encodeOverrideKeys(keys: Set<String>): String = keys.joinToString("\n")

        // The row index is the line index, which is RetroArch's cheat index by construction: the
        // native snapshot walks the list in order. A malformed line is dropped, never thrown on.
        internal fun decodeCheatRows(payload: String): List<RetroArchBridge.CheatRow> {
            if (payload.isEmpty()) return emptyList()
            return payload.split('\n').mapIndexedNotNull { index, line ->
                if (line.isBlank()) return@mapIndexedNotNull null
                val parts = splitEscaped(line)
                if (parts.size < 4) return@mapIndexedNotNull null
                RetroArchBridge.CheatRow(
                    index = index,
                    desc = parts[0],
                    code = parts[1],
                    enabled = parts[2] == "1",
                    supported = parts[3] == "1",
                )
            }
        }

        // Title and description are RetroAchievements server text, so they carry the same escaping
        // the cheat rows do. A malformed line is dropped, never thrown on.
        internal fun decodeAchievements(payload: String): List<AchievementInfo> {
            if (payload.isEmpty()) return emptyList()
            return payload.split('\n').mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val parts = splitEscaped(line)
                if (parts.size < 7) return@mapNotNull null
                AchievementInfo(
                    id = parts[0].toIntOrNull() ?: return@mapNotNull null,
                    title = parts[1],
                    description = parts[2],
                    points = parts[3].toIntOrNull() ?: 0,
                    unlocked = parts[4] == "1",
                    state = parts[5].toIntOrNull() ?: 0,
                    unlockTime = parts[6].toLongOrNull() ?: 0,
                )
            }
        }

        // Reverses ricotta_sb_escaped: backslash, pipe and newline are the only escapes.
        private fun splitEscaped(line: String): List<String> {
            val out = ArrayList<String>(4)
            val field = StringBuilder()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\\' && i + 1 < line.length -> {
                        val next = line[i + 1]
                        field.append(if (next == 'n') '\n' else next)
                        i += 2
                    }
                    c == '|' -> {
                        out.add(field.toString())
                        field.setLength(0)
                        i++
                    }
                    else -> {
                        field.append(c)
                        i++
                    }
                }
            }
            out.add(field.toString())
            return out
        }

        init {
            // The native library is already loaded by RetroActivityCommon
            // System.loadLibrary("retroarch-activity")
        }
    }
}
