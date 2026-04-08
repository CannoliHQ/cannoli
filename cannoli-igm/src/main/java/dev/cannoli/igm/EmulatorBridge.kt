package dev.cannoli.igm

import android.graphics.Bitmap

interface EmulatorBridge {
    // Lifecycle
    fun reset()
    fun quit()
    fun pause()
    fun unpause()
    fun isPaused(): Boolean

    // State management
    fun saveState(slot: Int)
    fun loadState(slot: Int)
    fun undoSaveState()
    fun undoLoadState()
    fun getStateSlotCount(): Int
    fun getStateThumbnail(slot: Int): Bitmap?
    fun stateExists(slot: Int): Boolean

    // Disc management
    fun getDiskCount(): Int
    fun getDiskIndex(): Int
    fun setDiskIndex(index: Int)
    fun getDiskLabel(index: Int): String?

    // Menu delegation
    fun openNativeMenu()
    fun openAchievementsMenu()

    // Menu close detection
    fun setOnNativeMenuClosed(callback: () -> Unit)

    // Capability flags
    val supportsNativeMenu: Boolean
    val supportsAchievements: Boolean
    val supportsUndo: Boolean
}
