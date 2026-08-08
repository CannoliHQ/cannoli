package dev.cannoli.igm

/**
 * What the in-game menu needs from the emulator it is running inside.
 *
 * Every command here is queued onto RetroArch's own thread rather than performed, so nothing
 * returns a result and the menu learns what happened from a callback. Reading what is on disk is
 * not part of this: [dev.cannoli.core.SaveSlotStore] owns the save slot files.
 */
interface RetroArchBridge {

    fun reset()
    fun quit()

    /** Queues a write of the slot. It has not happened when this returns. */
    fun saveState(slot: Int)
    fun loadState(slot: Int)

    /** RetroArch writes the auto slot itself while shutting down when this is on. */
    val savesOnQuit: Boolean

    val supportsAchievements: Boolean
    fun getAchievements(): List<AchievementInfo> = emptyList()

    fun getDiskCount(): Int
    fun getDiskIndex(): Int
    fun setDiskIndex(index: Int)

    fun openNativeMenu()
    fun setOnNativeMenuClosed(callback: () -> Unit)

    fun settingsProvider(): IgmSettingsProvider? = null

    /**
     * One row of RetroArch's live cheat list. [index] is the index every toggle must use: it is
     * observed by reading RetroArch back after a load, never inferred from the .cht file's order.
     */
    data class CheatRow(
        val index: Int,
        val desc: String,
        val code: String,
        val enabled: Boolean,
        val supported: Boolean,
    )

    /** Queues: drop the current list, load this file alone, clear every state, apply. */
    fun loadCheatFile(path: String) {}

    fun toggleCheat(index: Int) {}

    fun applyCheats() {}

    /** Fires on the emulator thread once a queued [loadCheatFile] has run. */
    fun setOnCheatsLoaded(callback: (List<CheatRow>) -> Unit) {}

    /** Enabling a cheat pauses hardcore achievements, so the menu warns first when this is on. */
    val hardcoreActive: Boolean get() = false
}
