package dev.cannoli.igm

interface RetroArchBridge {

    fun reset()
    fun quit()

    fun dropHeldCommands() {}

    fun saveState(slot: Int)
    fun loadState(slot: Int)

    fun undoSaveState()
    fun undoLoadState()

    fun toggleShowFps() {}

    fun toggleFastForward() {}

    fun setFastForwardHeld(held: Boolean) {}

    fun setRewindHeld(held: Boolean) {}

    val rewindEnabled: Boolean get() = false

    fun resetRewindBuffer() {}

    fun toggleShader() {}

    fun shaderToRestore(): String? = null

    data class ShortcutBinding(
        val action: ShortcutAction,
        val chord: Set<Int>,
    )

    fun shortcutBindings(): List<ShortcutBinding> = emptyList()

    fun setShortcutBinding(action: ShortcutAction, chord: Set<Int>) {}

    fun discardShortcuts() {}

    fun buttonRemap(): Map<Int, Int> = emptyMap()

    fun setButtonRemap(button: RemapButton, target: Int) {}

    fun buttonRemapBase(): Map<Int, Int> = ButtonRemap.identity()

    fun resetButtonRemap() {}

    fun buttonDescriptors(): Map<Int, String> = emptyMap()

    val savesOnQuit: Boolean

    fun forceSaveOnQuit() {}

    val savestatesAllowed: Boolean get() = true

    val supportsAchievements: Boolean
    fun getAchievements(): List<AchievementInfo> = emptyList()

    fun achievementsStatus(): String = ""

    fun getDiskCount(): Int
    fun getDiskIndex(): Int
    fun setDiskIndex(index: Int)

    fun players(): List<PlayerSlot> = emptyList()

    fun swapPlayers(a: Int, b: Int) {}

    fun openNativeMenu()
    fun setOnNativeMenuClosed(callback: () -> Unit)

    fun settingsProvider(): IgmSettingsProvider? = null

    fun coreGeometry(): IntArray? = null

    fun applyViewport(x: Int, y: Int, w: Int, h: Int): Boolean = false

    fun clearViewport(restoreAspectIdx: Int, restoreIntegerScale: Boolean): Boolean = false

    fun raAspectIndex(): Int = 22

    fun raIntegerScale(): Boolean = false

    fun raAspectValue(): Float = 0f

    data class CheatRow(
        val index: Int,
        val desc: String,
        val code: String,
        val enabled: Boolean,
        val supported: Boolean,
    )

    fun loadCheatFile(path: String) {}

    fun toggleCheat(index: Int) {}

    fun applyCheats() {}

    fun setOnCheatsLoaded(callback: (List<CheatRow>) -> Unit) {}

    val hardcoreActive: Boolean get() = false
}
