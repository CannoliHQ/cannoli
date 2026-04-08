package dev.cannoli.igm

import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

class IGMController(
    val bridge: EmulatorBridge,
    val gameTitle: String
) {
    val screenStack = mutableStateListOf<IGMScreen>()
    val currentScreen: IGMScreen? get() = screenStack.lastOrNull()
    val isOpen: Boolean get() = screenStack.isNotEmpty()

    var selectedSlotIndex = mutableIntStateOf(0)
    var slotThumbnail = mutableStateOf<Bitmap?>(null)
    var slotExists = mutableStateOf(false)
    var slotOccupied = mutableStateOf(emptyList<Boolean>())
    var undoLabel = mutableStateOf<String?>(null)

    private val saveSlotManager = SaveSlotManager()

    fun openMenu() {
        screenStack.clear()
        screenStack.add(IGMScreen.Menu())
        bridge.pause()
        refreshSlotInfo()
    }

    fun closeMenu() {
        screenStack.clear()
        bridge.unpause()
    }

    fun push(screen: IGMScreen) {
        screenStack.add(screen)
    }

    fun pop() {
        if (screenStack.size > 1) {
            screenStack.removeAt(screenStack.lastIndex)
        } else {
            closeMenu()
        }
    }

    fun replaceTop(screen: IGMScreen) {
        if (screenStack.isNotEmpty()) {
            screenStack[screenStack.lastIndex] = screen
        }
    }

    fun refreshSlotInfo() {
        val slot = saveSlotManager.slots[selectedSlotIndex.intValue]
        slotExists.value = bridge.stateExists(slot.index)
        slotThumbnail.value = bridge.getStateThumbnail(slot.index)
        slotOccupied.value = saveSlotManager.slots.map { bridge.stateExists(it.index) }
    }

    fun saveState() {
        val slot = saveSlotManager.slots[selectedSlotIndex.intValue]
        saveSlotManager.saveState(bridge, slot)
        refreshSlotInfo()
    }

    fun loadState() {
        val slot = saveSlotManager.slots[selectedSlotIndex.intValue]
        saveSlotManager.loadState(bridge, slot)
        refreshSlotInfo()
    }

    fun openNativeMenu() {
        screenStack.clear()
        bridge.openNativeMenu()
        bridge.setOnNativeMenuClosed { openMenu() }
    }

    fun openAchievements() {
        screenStack.clear()
        bridge.openAchievementsMenu()
        bridge.setOnNativeMenuClosed { openMenu() }
    }

    val slots get() = saveSlotManager.slots
    val currentSlot get() = saveSlotManager.slots[selectedSlotIndex.intValue]
}
