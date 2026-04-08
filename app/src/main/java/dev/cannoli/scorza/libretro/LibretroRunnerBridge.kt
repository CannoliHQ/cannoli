package dev.cannoli.scorza.libretro

import android.graphics.Bitmap
import dev.cannoli.igm.EmulatorBridge

class LibretroRunnerBridge(
    private val runner: LibretroRunner,
    private val oldSaveSlotManager: SaveSlotManager,
    private val onQuit: () -> Unit,
    private val onPause: () -> Unit,
    private val onUnpause: () -> Unit
) : EmulatorBridge {

    override val supportsNativeMenu: Boolean = false
    override val supportsAchievements: Boolean = true
    override val supportsUndo: Boolean = true

    @Volatile
    private var paused = false

    private var lastSaveSlot: Slot? = null

    private fun slotForIndex(index: Int): Slot = oldSaveSlotManager.slots[index]

    // Lifecycle

    override fun reset() = runner.reset()

    override fun quit() = onQuit()

    override fun pause() {
        paused = true
        onPause()
    }

    override fun unpause() {
        paused = false
        onUnpause()
    }

    override fun isPaused(): Boolean = paused

    // State management

    override fun saveState(slot: Int) {
        val s = slotForIndex(slot)
        oldSaveSlotManager.cacheForUndoSave(s)
        lastSaveSlot = s
        oldSaveSlotManager.saveState(runner, s)
    }

    override fun loadState(slot: Int) {
        val s = slotForIndex(slot)
        oldSaveSlotManager.cacheForUndoLoad(runner)
        oldSaveSlotManager.loadState(runner, s)
    }

    override fun undoSaveState() {
        val s = lastSaveSlot ?: return
        oldSaveSlotManager.performUndoSave(s)
    }

    override fun undoLoadState() {
        oldSaveSlotManager.performUndoLoad(runner)
    }

    override fun getStateSlotCount(): Int = oldSaveSlotManager.slots.size

    override fun getStateThumbnail(slot: Int): Bitmap? =
        oldSaveSlotManager.loadThumbnail(slotForIndex(slot))

    override fun stateExists(slot: Int): Boolean =
        oldSaveSlotManager.stateExists(slotForIndex(slot))

    // Disc management

    override fun getDiskCount(): Int = runner.getDiskCount()

    override fun getDiskIndex(): Int = runner.getDiskIndex()

    override fun setDiskIndex(index: Int) {
        runner.setDiskIndex(index)
    }

    override fun getDiskLabel(index: Int): String? = runner.getDiskLabel(index)

    // Menu delegation (no-ops for cannoli-launcher)

    override fun openNativeMenu() { /* no-op */ }

    override fun openAchievementsMenu() { /* no-op — achievements handled by RetroAchievementsManager */ }

    override fun setOnNativeMenuClosed(callback: () -> Unit) { /* no-op */ }
}
