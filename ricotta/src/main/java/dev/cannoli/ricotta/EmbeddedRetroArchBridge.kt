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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.cannoli.igm.AchievementInfo
import dev.cannoli.igm.ButtonRemap
import dev.cannoli.igm.RemapButton
import dev.cannoli.igm.RetroArchBridge
import dev.cannoli.igm.RaOverrideScope
import dev.cannoli.igm.MachineValue
import dev.cannoli.igm.RaApplyResult
import dev.cannoli.igm.RaOption
import dev.cannoli.igm.RaSetting
import dev.cannoli.igm.RaSettingType
import dev.cannoli.igm.RaScreenRow
import dev.cannoli.igm.RaSettingsHost
import dev.cannoli.igm.PlayerSlot
import dev.cannoli.igm.PortDeviceType
import dev.cannoli.igm.PortDevices
import dev.cannoli.igm.SegaPadLayouts
import dev.cannoli.ui.ButtonLabelSet

class EmbeddedRetroArchBridge(
    private val hardcoreInEffect: Boolean,
    private val cannoliRoot: String,
    private val platformTag: String,
    private val romBaseName: String,
    private val coreId: String,
    private val buttonLabelSet: ButtonLabelSet = ButtonLabelSet.PLUMBER,
) : RetroArchBridge, RaSettingsHost {

    override val supportsAchievements = true

    private var onMenuClosedCallback: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        nativeInit()
        nativeSetCannoliContext(cannoliRoot, platformTag, romBaseName, coreId)
        applyStoredShader()
        watchForRunloop()
    }

    fun destroy() {
        nativeDestroy()
    }

    @Suppress("unused")
    fun onNativeMenuClosed() {
        mainHandler.post {
            onMenuClosedCallback?.invoke()
        }
    }

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

    var onRunloopReady: (() -> Unit)? = null

    @Suppress("unused")
    fun onRunloopReady() {
        runloopReached = true
        mainHandler.post {
            applyStoredPortDevices(afterReset = false)
            applyRemap(storedRemap())
            onRunloopReady?.invoke()
        }
    }

    @Volatile
    private var runloopReached = false

    private fun watchForRunloop() {
        if (cannoliRoot.isEmpty()) return
        mainHandler.postDelayed({
            if (runloopReached) return@postDelayed
            runCatching {
                val out = File(cannoliRoot, "Logs/runloop.log")
                out.parentFile?.mkdirs()
                out.appendText(
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} " +
                        "no runloop after ${RUNLOOP_GRACE_MS}ms: the command pump never ran, so " +
                        "nothing the menu writes can apply. Check runloop.patch is applied.\n"
                )
            }
        }, RUNLOOP_GRACE_MS)
    }

    var onShortcutKey: ((keycode: Int, down: Boolean) -> Unit)? = null

    @Suppress("unused")
    fun onShortcutKey(keycode: Int, down: Boolean) {
        mainHandler.post {
            onShortcutKey?.invoke(keycode, down)
        }
    }

    var onShortcutAction: ((action: Int, kind: Int) -> Unit)? = null

    @Suppress("unused")
    fun onShortcutAction(action: Int, kind: Int) {
        mainHandler.post {
            onShortcutAction?.invoke(action, kind)
        }
    }

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

    var onCheevosLoad: ((CheevosLoad) -> Unit)? = null

    @Suppress("unused")
    fun onCheevosLoad(payload: String) {
        val p = CheevosLoad.parse(payload) ?: return
        mainHandler.post {
            onCheevosLoad?.invoke(p)
        }
    }

    private val cheevosOffline: CheevosOfflineHandler? by lazy {
        if (!cheevosOfflineAllowedFor(hardcoreInEffect)) return@lazy null
        if (cannoliRoot.isEmpty()) return@lazy null
        val root = java.io.File(java.io.File(java.io.File(cannoliRoot), "Config/Internal"), "RetroAchievements")
        val offline = java.io.File(root, "Offline")
        CheevosOfflineHandler(
            store = dev.cannoli.core.achievements.RaOfflineStore(offline),
            lookup = dev.cannoli.core.achievements.RaOfflineLookup(offline),
            pending = dev.cannoli.core.achievements.RaPendingUnlocks(java.io.File(root, "Pending")),
        ).also { it.platformTag = platformTag }
    }

    fun onCheevosRequest(postData: String): String? = cheevosOffline?.request(postData)

    fun onCheevosResponse(postData: String, body: String, httpStatus: Int): String? =
        cheevosOffline?.response(postData, body, httpStatus)

    fun onCheevosFailed(postData: String) {
        cheevosOffline?.failed(postData)
    }

    fun setIgmTriggerKeycodes(keycodes: IntArray) = nativeSetIgmTriggerKeycodes(keycodes)

    fun setShortcutChords(table: IntArray) = nativeSetShortcutChords(table)

    fun resolveShortcuts(
        global: Map<dev.cannoli.igm.ShortcutAction, Set<Int>>,
    ): Map<dev.cannoli.igm.ShortcutAction, Set<Int>> =
        dev.cannoli.igm.ShortcutAction.entries.associateWith { action ->
            when (val tier = storedTierValue(dev.cannoli.igm.ShortcutTable.keyFor(action))) {
                is TierValue.Set -> dev.cannoli.igm.ShortcutTable.parseChord(tier.value)
                is TierValue.Off -> emptySet()
                is TierValue.Inherit -> global[action].orEmpty()
            }
        }.filterValues { it.isNotEmpty() }

    fun resolveShortcutsStaged(
        global: Map<dev.cannoli.igm.ShortcutAction, Set<Int>>,
    ): Map<dev.cannoli.igm.ShortcutAction, Set<Int>> =
        shortcutBindings()
            .associate { it.action to it.chord }
            .filterValues { it.isNotEmpty() }

    fun setIGMVisible(visible: Boolean) {
        nativeSetIGMVisible(visible)
    }

    override fun reset() = nativeReset()

    override fun quit() = nativeQuit()

    override fun dropHeldCommands() = nativeDropHeld()

    fun pause() = nativePause()
    fun unpause() = nativeUnpause()
    override fun flushHeldCommands() = nativeFlushHeld()

    override val savesOnQuit: Boolean
        get() = raGetSetting("savestate_auto_save")?.machineValue?.raw == "true"

    override val savestatesAllowed: Boolean = savestatesAllowedFor(hardcoreInEffect)

    override fun saveState(slot: Int) = nativeSaveState(StateSlotPaths.retroArchStateSlot(slot))
    override fun loadState(slot: Int) = nativeLoadState(StateSlotPaths.retroArchStateSlot(slot))

    override fun undoSaveState() = nativeUndoSaveState()
    override fun undoLoadState() = nativeUndoLoadState()

    override fun forceSaveOnQuit() {
        raSetSetting("savestate_auto_save", MachineValue("true"))
    }

    var showFps: Boolean = false
        private set

    override fun toggleShowFps() {
        showFps = !showFps
        onShowFpsChanged?.invoke(showFps)
    }

    fun syncShowFps() {
        val on = raGetSetting("fps_show")?.machineValue?.raw == "true"
        if (on == showFps) return
        showFps = on
        onShowFpsChanged?.invoke(on)
    }

    var onShowFpsChanged: ((Boolean) -> Unit)? = null

    fun fps(): Float = nativeGetFps()

    var showDebug: Boolean = false
        private set

    var onShowDebugChanged: ((Boolean) -> Unit)? = null

    fun syncShowDebug() {
        val on = raGetSetting("statistics_show")?.machineValue?.raw == "true"
        if (on == showDebug) return
        showDebug = on
        onShowDebugChanged?.invoke(on)
    }

    fun debugStats(): List<Pair<String, String>> {
        val flat = nativeGetDebugStats() ?: return emptyList()
        return (flat.indices step 2).mapNotNull { i ->
            val name = flat.getOrNull(i) ?: return@mapNotNull null
            val value = flat.getOrNull(i + 1) ?: return@mapNotNull null
            name to value
        }
    }

    override fun toggleFastForward() = nativeToggleFastForward()

    override fun setFastForwardHeld(held: Boolean) = nativeSetFastForwardHeld(held)

    override fun setRewindHeld(held: Boolean) = nativeSetRewindHeld(held)

    override fun resetRewindBuffer() = nativeResetRewindBuffer()

    override val rewindEnabled: Boolean
        get() = raGetSetting("rewind_enable")?.machineValue?.raw == "true"

    private var shaderBeforeToggle: String? = null

    override fun toggleShader() {
        val live = appliedShader
        if (live != null) {
            shaderBeforeToggle = live
            nativeSetShaderPreset("")
            appliedShader = null
            return
        }
        val restore = shaderToRestore() ?: return
        nativeSetShaderPreset(restore)
        appliedShader = restore
    }

    override fun shaderToRestore(): String? =
        (shaderBeforeToggle ?: storedTierValue(KEY_SHADER).chosen)?.takeIf { File(it).isFile }

    override fun getAchievements(): List<AchievementInfo> {
        val decoded = decodeAchievements(nativeGetAchievementData())
        val queued = cheevosOffline?.pendingAchievementIds() ?: return decoded
        if (queued.isEmpty()) return decoded
        return decoded.map { if (it.id in queued) it.copy(pendingSync = true) else it }
    }

    override fun achievementsStatus(): String {
        val offline = cheevosOffline ?: return ""
        if (!offline.servedFromCache()) return ""
        val cachedAtMs = offline.cachedAtMs() ?: return ""
        val rel = android.text.format.DateUtils.getRelativeTimeSpanString(cachedAtMs).toString()
        return raStrings.achievementsOfflineCached(rel)
    }

    override fun getDiskCount() = nativeDiskCount()
    override fun getDiskIndex() = nativeDiskIndex()
    override fun setDiskIndex(index: Int) = nativeSetDiskIndex(index)

    private val queuedPortDevices = mutableMapOf<Int, Int>()

    override fun portDeviceTypes(port: Int): PortDevices? =
        decodePortDevices(nativePortDeviceTypes(port))?.let { devices ->
            queuedPortDevices[port]?.let { devices.copy(current = it) } ?: devices
        }

    override fun setPortDevice(port: Int, id: Int) {
        queuedPortDevices[port] = id
        nativeSetPortDevice(port, id)
    }

    override fun players(): List<PlayerSlot> = decodePlayers(nativePlayers())

    override fun swapPlayers(a: Int, b: Int) = nativeSwapPlayers(a, b)

    var raStrings: dev.cannoli.igm.RaOptionStrings = dev.cannoli.igm.RaOptionStrings()
    var onOpenNativeMenu: (() -> Unit)? = null
    var curatedSettings: Boolean = true

    var shadowedSettingsProvider: () -> Map<String, String> = { emptyMap() }
    override fun shadowedSettings(): Map<String, String> = shadowedSettingsProvider()

    override fun settingsProvider(): dev.cannoli.igm.IgmSettingsProvider {
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
        fun at(i: Int) = arr.getOrNull(i)?.takeIf { it.isNotEmpty() }
        return buildList {
            at(0)?.let { add(raStrings.infoCore to it) }
            at(1)?.let { add(raStrings.infoCoreVersion to it) }
            videoDriver().takeIf { it.isNotEmpty() }?.let { add(raStrings.infoRenderer to it) }
            at(2)?.let { add(raStrings.infoContent to stripRoot(it)) }
            at(3)?.let { add(raStrings.infoSave to stripRoot(it)) }
            cheevosStatus(at(6))?.let { add(raStrings.infoAchievements to it) }
            at(4)?.let { add(raStrings.infoGameId to it) }
            at(5)?.let { add(raStrings.infoHash to it) }
        }
    }

    private fun cheevosStatus(raw: String?): String? = when (raw?.toIntOrNull()) {
        0 -> if (hardcoreActive) raStrings.achievementsHardcore else raStrings.achievementsSoftcore
        1 -> raStrings.achievementsUnrecognised
        2 -> raStrings.achievementsNone
        3 -> raStrings.achievementsOffline
        else -> null
    }

    private fun stripRoot(path: String): String =
        if (cannoliRoot.isNotEmpty() && path.startsWith(cannoliRoot))
            path.removePrefix(cannoliRoot).removePrefix("/")
        else path

    private var shaderPresence: Boolean? = null

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

    override fun shaderEntries(path: List<String>): List<ShaderEntry> {
        if (cannoliRoot.isEmpty()) return emptyList()
        val root = ShaderCatalog.shadersDir(File(cannoliRoot))
        return ShaderCatalog.list(root, path, videoDriver(), shaderIndex(root))
    }

    override fun applyShaderPreset(path: List<String>, name: String): Set<String> {
        val preset = shaderPresetPath(path, name) ?: return emptySet()
        cleared.remove(KEY_SHADER)
        nativeSetShaderPreset(preset)
        raSetSetting(SHADER_ENABLE_KEY, MachineValue("true"))
        appliedShader = preset
        return setOf(KEY_SHADER, SHADER_ENABLE_KEY)
    }

    private var appliedShader: String? = null

    override fun appliedShaderPreset(): String? = appliedShader

    private var loadedIndex: ShaderIndex.Index? = null
    private var indexLoaded = false

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

    private fun videoDriver(): String = raGetSetting("video_driver")?.machineValue?.raw.orEmpty()

    private var overlayNames: List<String>? = null

    override fun overlays(): List<String> = overlayNames ?: rescanOverlays()

    fun rescanOverlays(): List<String> {
        val names =
            if (cannoliRoot.isEmpty()) emptyList()
            else OverlayCatalog.list(OverlayCatalog.platformDir(File(cannoliRoot), platformTag))
        overlayNames = names
        return names
    }

    private val described = java.util.concurrent.ConcurrentHashMap<String, RaSetting>()

    override fun raGetSetting(key: String): RaSetting? {
        val shape = described[key] ?: describe(key)?.also { described[key] = it } ?: return null
        val now = nativeRaValue(key)?.split('') ?: return null
        val machine = now.firstOrNull() ?: return null
        return shape.copy(
            machineValue = MachineValue(machine),
            displayValue = now.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: machine,
        )
    }

    private fun describe(key: String): RaSetting? {
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

    private fun raSetSetting(key: String, value: MachineValue): Boolean =
        nativeRaSetSetting(key, value.raw)

    private val applyTokens = java.util.concurrent.atomic.AtomicInteger()
    private val pendingApplies = java.util.concurrent.ConcurrentHashMap<Int, (RaApplyResult?) -> Unit>()

    override fun raApply(
        key: String,
        value: MachineValue,
        watch: Collection<String>,
        onDone: (RaApplyResult?) -> Unit,
    ): Boolean {
        val token = applyTokens.incrementAndGet()
        pendingApplies[token] = onDone
        val queued = nativeRaApply(token, key, value.raw, watch.filterNot { it == key }.toTypedArray())
        if (!queued) pendingApplies.remove(token)
        return queued
    }

    @Suppress("unused")
    fun onRaApplyDone(token: Int, value: String?, moved: Array<String>) {
        described.clear()
        val done = pendingApplies.remove(token) ?: return
        val result = value?.let { RaApplyResult(MachineValue(it), decodeMoved(moved)) }
        mainHandler.post { done(result) }
    }

    override fun coreGeometry(): IntArray? = nativeCoreGeometry()

    override fun applyViewport(x: Int, y: Int, w: Int, h: Int): Boolean =
        nativeApplyViewport(x, y, w, h)

    override fun clearViewport(restoreAspectIdx: Int, restoreIntegerScale: Boolean): Boolean =
        nativeClearViewport(restoreAspectIdx, restoreIntegerScale)

    override fun raAspectIndex(): Int = nativeRaAspectIndex()

    override fun raIntegerScale(): Boolean = nativeRaIntegerScale()

    override fun raAspectValue(): Float = nativeRaAspectValue()

    override fun raScreenRows(label: String): List<RaScreenRow> =
        nativeRaScreenRows(label).orEmpty().mapNotNull { encoded ->
            val f = encoded.split('\u001f')
            if (f.size < 3 || f[0].isBlank()) null
            else RaScreenRow(key = f[0].trim(), label = f[1].trim(), isMenu = f[2] == "1")
        }

    var cannoliOverlayName: String? = null

    var onCannoliSaved: (() -> Unit)? = null
    var onCannoliRevert: (() -> Unit)? = null

    override fun revertCannoliOverride() {
        discardShortcuts()
        if (pendingRemap.isNotEmpty()) {
            pendingRemap.clear()
            applyRemap(storedRemap())
        }
        shaderBeforeEdit.let { before ->
            if (before != appliedShader) {
                nativeSetShaderPreset(before.orEmpty())
                appliedShader = before
            }
        }
        cleared.clear()
        onCannoliRevert?.invoke()
    }

    private var shaderBeforeEdit: String? = null

    fun latchShaderForEdit() {
        shaderBeforeEdit = appliedShaderPreset()
    }

    private val pendingShortcuts = LinkedHashMap<dev.cannoli.igm.ShortcutAction, TierValue>()

    private val pendingRemap = LinkedHashMap<Int, Int>()

    private var appliedRemap: Map<Int, Int> = ButtonRemap.identity()

    private val routedRemap: Map<Int, Int> by lazy { SegaPadLayouts.routed(coreId, buttonLabelSet) }

    override fun buttonRemapBase(): Map<Int, Int> = ButtonRemap.identity() + routedRemap

    override fun resetButtonRemap() {
        pendingRemap.putAll(resetRemapStaging(routedRemap))
        applyRemap(buttonRemap())
    }

    override fun buttonRemap(): Map<Int, Int> = storedRemap() + pendingRemap.mapValues { (id, target) ->
        if (target == ButtonRemap.INHERIT) routedRemap[id] ?: id else target
    }

    override fun setButtonRemap(button: RemapButton, target: Int) {
        pendingRemap[button.id] = target
        applyRemap(buttonRemap())
    }

    private fun storedRemap(): Map<Int, Int> = remapFromTiers(
        game = gameTier()?.let(::readTier).orEmpty(),
        system = systemTier()?.let(::readTier).orEmpty(),
        base = routedRemap,
    )

    private fun applyRemap(next: Map<Int, Int>) {
        for ((id, target) in remapChanges(appliedRemap, next)) {
            nativeSetButtonRemap(ButtonRemap.ALL_PORTS, id, target)
        }
        appliedRemap = next
    }

    private fun stagedRemapValues(): Map<String, TierValue> = stagedRemapTierValues(pendingRemap)

    override fun buttonDescriptors(): Map<Int, String> = decodeDescriptors(nativeButtonDescriptors())

    var globalShortcuts: Map<dev.cannoli.igm.ShortcutAction, Set<Int>> = emptyMap()

    var onShortcutsStaged: (() -> Unit)? = null

    override fun shortcutBindings(): List<dev.cannoli.igm.RetroArchBridge.ShortcutBinding> =
        dev.cannoli.igm.ShortcutAction.entries.map { action ->
            val staged = pendingShortcuts[action]
            val value = if (pendingShortcuts.containsKey(action)) staged
                else storedTierValue(dev.cannoli.igm.ShortcutTable.keyFor(action))
            val chord = when (value) {
                is TierValue.Set -> dev.cannoli.igm.ShortcutTable.parseChord(value.value)
                is TierValue.Off -> emptySet()
                else -> globalShortcuts[action].orEmpty()
            }
            dev.cannoli.igm.RetroArchBridge.ShortcutBinding(action, chord)
        }

    override fun setShortcutBinding(action: dev.cannoli.igm.ShortcutAction, chord: Set<Int>) {
        pendingShortcuts[action] = if (chord.isEmpty()) TierValue.Off
            else TierValue.Set(dev.cannoli.igm.ShortcutTable.formatChord(chord))
        onShortcutsStaged?.invoke()
    }

    override fun discardShortcuts() {
        pendingShortcuts.clear()
        onShortcutsStaged?.invoke()
    }

    private fun stagedShortcutValues(): Map<String, TierValue> =
        pendingShortcuts.entries.associate { (action, value) ->
            dev.cannoli.igm.ShortcutTable.keyFor(action) to value
        }

    override fun saveCannoliOverride(scope: RaOverrideScope, changed: Set<String>) {
        val staged = stagedShortcutValues() + stagedRemapValues()
        val mine = (CANNOLI_KEYS + staged.keys).filter { it in changed }
        if (mine.isEmpty() && cleared.isEmpty()) return
        if (cannoliRoot.isEmpty()) return
        val target = tierFile(scope) ?: return

        val values = LinkedHashMap<String, TierValue>()
        for (key in mine) {
            if (key in cleared) continue
            when {
                key == KEY_OVERLAY -> values[key] =
                    cannoliOverlayName?.let { TierValue.Set(it) } ?: TierValue.Off
                key == KEY_SHADER -> shaderTierValue(scope)?.let { values[key] = it }
                else -> staged[key]?.let { values[key] = it }
            }
        }
        writeTier(target, values)

        if (writesRemap(values)) applyRemap(storedRemap())

        if (cleared.isNotEmpty()) {
            gameTier()?.let { writeTier(it, cleared.associateWith { TierValue.Inherit }) }
        }
        cleared.clear()
        pendingShortcuts.clear()
        pendingRemap.clear()
        onCannoliSaved?.invoke()
    }

    private fun shaderTierValue(scope: RaOverrideScope): TierValue? {
        val chain = pendingChain
            ?: return appliedShader?.let { TierValue.Set(it) } ?: TierValue.Off
        if (chain.passes.isEmpty()) {
            applyShaderChain()
            return TierValue.Off
        }
        return applyShaderChain(autoPresetName(scope))?.let { TierValue.Set(it) }
    }

    private val cleared = mutableSetOf<String>()

    fun overridesAtGame(key: String): Boolean =
        key !in cleared && gameTier()?.let { tierValue(it, key) } !is TierValue.Inherit?

    private var pendingChain: ShaderPreset? = null

    override fun setShaderChain(chain: ShaderPreset?) {
        pendingChain = chain
    }

    override fun applyShaderChain(saveAs: String?): String? {
        val chain = pendingChain ?: return null
        if (cannoliRoot.isEmpty()) return null
        val shaders = ShaderCatalog.shadersDir(File(cannoliRoot))
        val ext = ShaderCatalog.presetExtension(videoDriver())
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
        nativeSetShaderPreset(inherited.orEmpty())
        appliedShader = inherited
        return setOf(KEY_SHADER)
    }

    private fun clearGameOverride(key: String): String? {
        cleared.add(key)
        return systemTier()?.let { tierValue(it, key) }?.chosen
    }

    fun pickOverlay(name: String?) {
        cleared.remove(KEY_OVERLAY)
        cannoliOverlayName = name
    }

    fun restoreOverlayDefault(): String? {
        val inherited = clearGameOverride(KEY_OVERLAY)
        cannoliOverlayName = inherited
        return inherited
    }

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

    fun storedOverlayName(): String? = storedTierValue(KEY_OVERLAY).chosen

    private fun tierDir(scope: RaOverrideScope): File? {
        if (cannoliRoot.isEmpty()) return null
        val root = File(cannoliRoot)
        return when (scope) {
            RaOverrideScope.SYSTEM -> File(File(root, OverrideTiers.SYSTEMS_DIR), platformTag)
            RaOverrideScope.GAME -> {
                if (romBaseName.isEmpty()) return null
                File(File(File(root, OverrideTiers.GAMES_DIR), platformTag), romBaseName)
            }
        }
    }

    override fun hasOverrides(scope: RaOverrideScope): Boolean =
        tierDir(scope)?.listFiles()?.any { it.isFile } == true

    override fun resetOverrides(scope: RaOverrideScope) {
        val dir = tierDir(scope) ?: return
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }

        pendingShortcuts.clear()
        pendingRemap.clear()
        cleared.clear()
        pendingChain = null

        val shader = storedTierValue(KEY_SHADER).chosen?.takeIf { File(it).isFile }
        nativeSetShaderPreset(shader.orEmpty())
        appliedShader = shader
        cannoliOverlayName = storedOverlayName()

        applyStoredPortDevices(afterReset = true)
        applyRemap(storedRemap())

        onShortcutsStaged?.invoke()
        onCannoliReset?.invoke()
    }

    private fun applyStoredPortDevices(afterReset: Boolean) {
        val files = coreTierFiles(cannoliRoot, platformTag, romBaseName, coreId)
        for (port in 0 until PortDevices.PLAYER_ROWS) {
            val devices = portDeviceTypes(port) ?: continue
            val offered = devices.choices.mapTo(mutableSetOf()) { it.id }
            val id = resolvePortDevice(storedInt(files, PortDevices.keyFor(port)), offered, afterReset) ?: continue
            if (id != devices.current) setPortDevice(port, id)
        }
    }

    var onCannoliReset: (() -> Unit)? = null

    private fun applyStoredShader() {
        val stored = storedTierValue(KEY_SHADER).chosen ?: return
        if (!File(stored).isFile) return
        nativeSetShaderPreset(stored)
        appliedShader = stored
    }

    private fun storedTierValue(key: String): TierValue =
        listOfNotNull(gameTier(), systemTier())
            .map { tierValue(it, key) }
            .firstOrNull { it !is TierValue.Inherit }
            ?: TierValue.Inherit

    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {
        val encoded = if (scope == RaOverrideScope.GAME) 1 else 0
        nativeRaSaveOverride(encoded, encodeOverrideKeys(keys))
    }

    private var onRaApplied: ((String, String) -> Unit)? = null

    fun setOnRaSettingApplied(callback: ((key: String, value: String) -> Unit)?) {
        onRaApplied = callback
    }

    @Suppress("unused")
    fun onRaSettingApplied(key: String, value: String) {
        described.clear()
        mainHandler.post { onRaApplied?.invoke(key, value) }
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
    private external fun nativeDropHeld()
    private external fun nativePause()
    private external fun nativeUnpause()
    private external fun nativeIsPaused(): Boolean
    private external fun nativeMenuToggle()
    private external fun nativeFlushHeld()
    private external fun nativeCoreOptionKeys(): Array<String>?
    private external fun nativeSystemInfo(): Array<String>?
    private external fun nativeDiskCount(): Int
    private external fun nativeDiskIndex(): Int
    private external fun nativeDiskLabel(index: Int): String?
    private external fun nativeSetDiskIndex(index: Int)
    private external fun nativePortDeviceTypes(port: Int): Array<String>?
    private external fun nativeButtonDescriptors(): Array<String>?
    private external fun nativeSetPortDevice(port: Int, id: Int)
    private external fun nativeSetButtonRemap(port: Int, source: Int, target: Int)
    private external fun nativePlayers(): Array<String>?
    private external fun nativeSwapPlayers(a: Int, b: Int)
    private external fun nativeSetIGMVisible(visible: Boolean)
    private external fun nativeSetIgmTriggerKeycodes(keycodes: IntArray)
    private external fun nativeSetShortcutChords(table: IntArray)
    private external fun nativeGetFps(): Float
    private external fun nativeGetDebugStats(): Array<String>?
    private external fun nativeToggleFastForward()
    private external fun nativeSetFastForwardHeld(held: Boolean)
    private external fun nativeSetRewindHeld(held: Boolean)
    private external fun nativeResetRewindBuffer()

    private external fun nativeGetAchievementData(): String
    private external fun nativeCheatLoadFile(path: String)
    private external fun nativeCheatToggle(index: Int)
    private external fun nativeCheatApply()
    private external fun nativeCheatHardcoreActive(): Boolean
    private external fun nativeRaGetSetting(key: String): Array<String>?
    private external fun nativeRaValue(key: String): String?
    private external fun nativeRaSetSetting(key: String, value: String): Boolean
    private external fun nativeRaApply(token: Int, key: String, value: String, watch: Array<String>): Boolean
    private external fun nativeSetShaderPreset(path: String)

    private external fun nativeRaSaveOverride(scope: Int, keys: String)
    private external fun nativeCoreGeometry(): IntArray?
    private external fun nativeApplyViewport(x: Int, y: Int, w: Int, h: Int): Boolean
    private external fun nativeClearViewport(restoreAspectIdx: Int, restoreIntegerScale: Boolean): Boolean
    private external fun nativeRaAspectIndex(): Int
    private external fun nativeRaAspectValue(): Float
    private external fun nativeRaIntegerScale(): Boolean

    companion object {
        private val FILENAME_RESERVED = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        private const val WORKING_CHAIN = ".cannoli_chain"

        private const val RUNLOOP_GRACE_MS = 20_000L

        const val KEY_OVERLAY = OverrideTiers.KEY_OVERLAY
        const val KEY_SHADER = OverrideTiers.KEY_SHADER

        private val CANNOLI_KEYS = listOf(KEY_OVERLAY, KEY_SHADER)
        private const val SHADER_ENABLE_KEY = "video_shader_enable"
        internal fun savestatesAllowedFor(hardcoreInEffect: Boolean): Boolean = !hardcoreInEffect

        internal fun cheevosOfflineAllowedFor(hardcoreInEffect: Boolean): Boolean = !hardcoreInEffect

        internal fun encodeOverrideKeys(keys: Set<String>): String = keys.joinToString("\n")

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

        internal fun decodePortDevices(fields: Array<String>?): PortDevices? {
            if (fields == null || fields.size < 2) return null
            val current = fields[1].toIntOrNull() ?: return null
            val types = fields.drop(2).chunked(2).mapNotNull { pair ->
                if (pair.size < 2) return@mapNotNull null
                PortDeviceType(pair[0].toIntOrNull() ?: return@mapNotNull null, pair[1])
            }
            return PortDevices(current, types)
        }

        internal fun decodeDescriptors(fields: Array<String>?): Map<Int, String> =
            fields.orEmpty().toList().chunked(2).mapNotNull { pair ->
                val id = pair.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val name = pair.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to name
            }.toMap()

        internal fun decodePlayers(fields: Array<String>?): List<PlayerSlot> {
            if (fields == null) return emptyList()
            return fields.toList().chunked(6).mapIndexedNotNull { player, row ->
                if (row.size < 6) return@mapIndexedNotNull null
                PlayerSlot(
                    player = player,
                    padIndex = row[1].toIntOrNull() ?: return@mapIndexedNotNull null,
                    name = row[5].ifEmpty { null },
                    setNumber = row[3].toIntOrNull() ?: 0,
                )
            }
        }

        internal fun resolvePortDevice(stored: Int?, offered: Set<Int>, afterReset: Boolean): Int? = when {
            stored != null && stored in offered -> stored
            afterReset -> PortDevices.RETRO_DEVICE_JOYPAD
            else -> null
        }

        internal fun coreTierFiles(root: String, tag: String, base: String, core: String): List<File> {
            if (root.isEmpty() || core.isEmpty()) return emptyList()
            val system = File(File(File(root, OverrideTiers.SYSTEMS_DIR), tag), "$core.cfg")
            if (base.isEmpty()) return listOf(system)
            val game = File(File(File(File(root, OverrideTiers.GAMES_DIR), tag), base), "$core.cfg")
            return listOf(game, system)
        }

        internal fun remapFromTiers(
            game: Map<String, String>,
            system: Map<String, String>,
            base: Map<Int, Int> = emptyMap(),
        ): Map<Int, Int> = RemapButton.entries.associate { button ->
            val key = ButtonRemap.keyFor(button)
            val value = ButtonRemap.valueOf(game[key])
                ?: ButtonRemap.valueOf(system[key])
                ?: base[button.id]
                ?: button.id
            button.id to value
        }

        internal fun stagedRemapTierValues(pending: Map<Int, Int>): Map<String, TierValue> =
            pending.entries.mapNotNull { (id, target) ->
                RemapButton.forId(id)?.let {
                    ButtonRemap.keyFor(it) to
                        if (target == ButtonRemap.INHERIT) TierValue.Inherit else TierValue.Set(target.toString())
                }
            }.toMap()

        internal fun writesRemap(values: Map<String, TierValue>): Boolean =
            values.keys.any { ButtonRemap.buttonForKey(it) != null }

        internal fun remapChanges(applied: Map<Int, Int>, next: Map<Int, Int>): Map<Int, Int> =
            next.filter { (id, target) -> applied[id] != target }

        internal fun resetRemapStaging(routed: Map<Int, Int>): Map<Int, Int> =
            RemapButton.entries.associate { it.id to if (routed.containsKey(it.id)) ButtonRemap.INHERIT else it.id }

        internal fun storedInt(files: List<File>, key: String): Int? = files.firstNotNullOfOrNull { file ->
            val raw = try {
                if (file.isFile) RetroArchConfigComposer.parse(file.readText())[key] else null
            } catch (_: Exception) {
                null
            }
            raw?.toIntOrNull()
        }

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

        @JvmStatic external fun nativeExitProcess()
    }
}
