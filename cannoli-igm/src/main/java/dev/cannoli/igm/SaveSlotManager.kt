package dev.cannoli.igm

import android.graphics.Bitmap

class SaveSlotManager {

    data class Slot(val index: Int, val label: String)

    val slots = listOf(Slot(0, "Auto")) +
        (0..9).map { Slot(it + 1, "Slot $it") }

    fun saveState(bridge: EmulatorBridge, slot: Slot) = bridge.saveState(slot.index)
    fun loadState(bridge: EmulatorBridge, slot: Slot) = bridge.loadState(slot.index)
    fun stateExists(bridge: EmulatorBridge, slot: Slot) = bridge.stateExists(slot.index)
    fun loadThumbnail(bridge: EmulatorBridge, slot: Slot): Bitmap? = bridge.getStateThumbnail(slot.index)
    fun undoSave(bridge: EmulatorBridge) = bridge.undoSaveState()
    fun undoLoad(bridge: EmulatorBridge) = bridge.undoLoadState()
}
