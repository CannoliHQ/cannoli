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
}
