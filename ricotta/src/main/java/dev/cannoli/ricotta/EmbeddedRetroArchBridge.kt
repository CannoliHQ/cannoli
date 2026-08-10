package dev.cannoli.ricotta

import android.os.Handler
import android.os.Looper
import dev.cannoli.core.StateSlotPaths
import dev.cannoli.igm.AchievementInfo
import dev.cannoli.igm.RetroArchBridge
import dev.cannoli.igm.RaOverrideScope
import dev.cannoli.igm.RaSetting
import dev.cannoli.igm.RaSettingType
import dev.cannoli.igm.RaSettingsHost

class EmbeddedRetroArchBridge(
    private val stateBasePath: String,
    private val hardcoreInEffect: Boolean,
    cannoliRoot: String,
    platformTag: String,
    romBaseName: String,
) : RetroArchBridge, RaSettingsHost {

    override val supportsAchievements = true

    private var onMenuClosedCallback: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        nativeInit()
        // The IGM's "Save for game/platform" rows write to Cannoli's own override tiers, which are
        // keyed by these. Set before the IGM is interactive so no save can precede it.
        nativeSetCannoliContext(cannoliRoot, platformTag, romBaseName)
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

    override fun settingsProvider(): dev.cannoli.igm.IgmSettingsProvider =
        dev.cannoli.igm.RaIgmSettingsProvider(
            host = this,
            strings = raStrings,
            debugBuild = com.retroarch.BuildConfig.DEBUG,
            onOpenNativeMenu = { onOpenNativeMenu?.invoke() },
        )

    override fun coreOptions(): List<dev.cannoli.igm.CoreOptionRef> =
        nativeCoreOptionKeys()?.map { entry ->
            val parts = entry.split('|', limit = 3)
            dev.cannoli.igm.CoreOptionRef(
                key = "$CORE_OPTION_PREFIX${parts[0]}",
                categoryKey = parts.getOrNull(1).orEmpty(),
                categoryLabel = parts.getOrNull(2).orEmpty(),
            )
        } ?: emptyList()

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
        )
    }

    override fun raSetSetting(key: String, value: String): Boolean {
        nativeRaSetSetting(key, value)
        return true
    }

    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {
        nativeRaSaveOverride(if (scope == RaOverrideScope.GAME) 1 else 0, encodeOverrideKeys(keys))
    }

    // Host wires these to its SharedPreferences (needs a Context the bridge lacks).
    var localToggleGet: ((String, Boolean) -> Boolean)? = null
    var localToggleSet: ((String, Boolean) -> Unit)? = null

    override fun getLocalToggle(key: String, default: Boolean): Boolean =
        localToggleGet?.invoke(key, default) ?: default

    override fun setLocalToggle(key: String, value: Boolean) {
        localToggleSet?.invoke(key, value)
    }

    private var onRaAppliedCallback: ((String, String) -> Unit)? = null

    override fun setOnRaSettingApplied(callback: (key: String, value: String) -> Unit) {
        onRaAppliedCallback = callback
    }

    @Suppress("unused")
    fun onRaSettingApplied(key: String, value: String) {
        mainHandler.post { onRaAppliedCallback?.invoke(key, value) }
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
    private external fun nativeSetCannoliContext(root: String, tag: String, base: String)
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
    private external fun nativeDiskCount(): Int
    private external fun nativeDiskIndex(): Int
    private external fun nativeDiskLabel(index: Int): String?
    private external fun nativeSetDiskIndex(index: Int)
    private external fun nativeSetIGMVisible(visible: Boolean)
    private external fun nativeSetIgmTriggerKeycodes(keycodes: IntArray)
    private external fun nativeGetAchievementData(): String
    private external fun nativeCheatLoadFile(path: String)
    private external fun nativeCheatToggle(index: Int)
    private external fun nativeCheatApply()
    private external fun nativeCheatHardcoreActive(): Boolean
    private external fun nativeRaGetSetting(key: String): Array<String>?
    private external fun nativeRaSetSetting(key: String, value: String)
    private external fun nativeRaSaveOverride(scope: Int, keys: String)

    companion object {
        // Matches RICOTTA_CORE_OPT_PREFIX in ricotta_bridge.c.
        const val CORE_OPTION_PREFIX = "core::"

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
