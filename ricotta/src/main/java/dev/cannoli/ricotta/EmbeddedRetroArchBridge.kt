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
    private val stateBasePath: String
) : RetroArchBridge, RaSettingsHost {

    override val supportsAchievements = true

    private var onMenuClosedCallback: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        nativeInit()
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

    override fun raSaveOverride(scope: RaOverrideScope) {
        nativeRaSaveOverride(if (scope == RaOverrideScope.GAME) 1 else 0)
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

    override fun openNativeMenu() = nativeMenuToggle()

    override fun setOnNativeMenuClosed(callback: () -> Unit) {
        onMenuClosedCallback = callback
    }

    // Native methods
    private external fun nativeInit()
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
    private external fun nativeRaGetSetting(key: String): Array<String>?
    private external fun nativeRaSetSetting(key: String, value: String)
    private external fun nativeRaSaveOverride(scope: Int)

    companion object {
        // Matches RICOTTA_CORE_OPT_PREFIX in ricotta_bridge.c.
        const val CORE_OPTION_PREFIX = "core::"

        init {
            // The native library is already loaded by RetroActivityCommon
            // System.loadLibrary("retroarch-activity")
        }
    }
}
