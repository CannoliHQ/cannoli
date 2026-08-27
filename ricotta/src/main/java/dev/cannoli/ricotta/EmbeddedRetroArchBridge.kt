package dev.cannoli.ricotta

import android.os.Handler
import android.os.Looper
import dev.cannoli.core.StateSlotPaths
import dev.cannoli.core.config.OverrideTiers
import dev.cannoli.core.config.RetroArchConfigComposer
import dev.cannoli.core.overlay.OverlayCatalog
import java.io.File
import dev.cannoli.igm.AchievementInfo
import dev.cannoli.igm.RetroArchBridge
import dev.cannoli.igm.RaOverrideScope
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
        get() = raGetSetting("savestate_auto_save")?.value == "true"

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

    override fun getAchievements(): List<AchievementInfo> {
        val raw = nativeGetAchievementData()
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('|', limit = 7)
            if (parts.size < 7) return@mapNotNull null
            AchievementInfo(
                id = parts[0].toIntOrNull() ?: return@mapNotNull null,
                title = parts[1],
                description = parts[2],
                points = parts[3].toIntOrNull() ?: 0,
                unlocked = parts[4] == "1",
                state = parts[5].toIntOrNull() ?: 0,
                unlockTime = parts[6].toLongOrNull() ?: 0
            )
        }
    }

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

    override fun settingsProvider(): dev.cannoli.igm.IgmSettingsProvider =
        dev.cannoli.igm.RaIgmSettingsProvider(
            host = this,
            strings = raStrings,
            curated = curatedSettings,
        )

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
        val arr = nativeRaGetSetting(key) ?: return null
        if (arr.size < 8) return null
        val type = when (arr[1]) {
            "BOOL" -> RaSettingType.BOOL
            "INT" -> RaSettingType.INT
            "FLOAT" -> RaSettingType.FLOAT
            "ENUM" -> RaSettingType.ENUM
            else -> RaSettingType.STRING_RO
        }
        return RaSetting(
            key = key,
            label = arr[0],
            type = type,
            value = arr[2],
            min = arr[3].toFloatOrNull(),
            max = arr[4].toFloatOrNull(),
            step = arr[5].toFloatOrNull(),
            options = arr[6].takeIf { it.isNotEmpty() }?.split("|"),
            requiresRestart = arr[7] == "1",
            // Core options come back from the same call with eight elements and no raw value.
            rawValue = arr.getOrNull(8)?.takeIf { it.isNotEmpty() },
            description = arr.getOrNull(9)?.takeIf { it.isNotEmpty() },
        )
    }

    // False means the key resolves to nothing, so the write was never queued. The apply itself is
    // asynchronous and its outcome arrives later through the applied echo.
    override fun raSetSetting(key: String, value: String): Boolean = nativeRaSetSetting(key, value)

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

    override fun revertCannoliOverride() { onCannoliRevert?.invoke() }

    override fun saveCannoliOverride(scope: RaOverrideScope, changed: Set<String>) {
        // Only when this visit actually moved the overlay. Otherwise saving any setting at platform
        // scope would copy a game's bezel onto the whole platform.
        if (KEY_OVERLAY !in changed) return
        if (cannoliRoot.isEmpty()) return
        val root = File(cannoliRoot)
        val tier = when (scope) {
            RaOverrideScope.GAME ->
                if (romBaseName.isEmpty()) return
                else File(File(File(root, OverrideTiers.GAMES_DIR), platformTag), romBaseName)
            RaOverrideScope.SYSTEM -> File(File(root, OverrideTiers.SYSTEMS_DIR), platformTag)
        }
        writeSharedTier(File(tier, "${OverrideTiers.SHARED}.cfg"))
        onCannoliSaved?.invoke()
    }

    // Merged rather than rewritten: the tier is shared with anything else core-independent, and a
    // shader will land in the same file.
    private fun writeSharedTier(file: File) {
        val existing = try {
            if (file.isFile) RetroArchConfigComposer.parse(file.readText()) else emptyMap()
        } catch (_: Exception) { emptyMap() }
        val merged = LinkedHashMap(existing)
        val name = cannoliOverlayName
        if (name.isNullOrEmpty()) merged.remove(KEY_OVERLAY) else merged[KEY_OVERLAY] = name
        try {
            file.parentFile?.mkdirs()
            if (merged.isEmpty()) {
                file.delete()
            } else {
                file.writeText(merged.entries.joinToString("\n") { "${it.key} = \"${it.value}\"" } + "\n")
            }
        } catch (_: Exception) {
        }
    }

    /** The stored overlay for this game, game scope winning, or null when none is set. */
    fun storedOverlayName(): String? {
        if (cannoliRoot.isEmpty()) return null
        val root = File(cannoliRoot)
        val game = File(File(File(File(root, OverrideTiers.GAMES_DIR), platformTag), romBaseName), "${OverrideTiers.SHARED}.cfg")
        val system = File(File(File(root, OverrideTiers.SYSTEMS_DIR), platformTag), "${OverrideTiers.SHARED}.cfg")
        return sequenceOf(game, system)
            .mapNotNull { f ->
                try { if (f.isFile) RetroArchConfigComposer.parse(f.readText())[KEY_OVERLAY] else null }
                catch (_: Exception) { null }
            }
            .firstOrNull { it.isNotBlank() }
    }

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
    private external fun nativeRaSaveOverride(scope: Int, keys: String)
    private external fun nativeCoreGeometry(): IntArray?
    private external fun nativeApplyViewport(x: Int, y: Int, w: Int, h: Int): Boolean
    private external fun nativeClearViewport(restoreAspectIdx: Int, restoreIntegerScale: Boolean): Boolean
    private external fun nativeRaAspectIndex(): Int
    private external fun nativeRaAspectValue(): Float
    private external fun nativeRaIntegerScale(): Boolean

    companion object {
        private const val KEY_OVERLAY = "cannoli_overlay"
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
