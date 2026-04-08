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
        refreshSlotInfo()
    }

    fun closeMenu() {
        screenStack.clear()
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

    /** Callback for when the IGM wants to close (hide the overlay) */
    var onClose: (() -> Unit)? = null

    /** Callback for when the IGM wants to open the native menu */
    var onOpenNativeMenu: (() -> Unit)? = null

    /**
     * Handle a key event from the gamepad.
     * Android keycodes: DPAD_UP=19, DPAD_DOWN=20, DPAD_LEFT=21, DPAD_RIGHT=22,
     * BUTTON_A=96, BUTTON_B=97, BACK=4
     */
    fun handleKeyDown(keycode: Int) {
        val screen = currentScreen ?: return

        when (screen) {
            is IGMScreen.Menu -> handleMenuKey(screen, keycode)
            else -> {}
        }
    }

    private fun handleMenuKey(screen: IGMScreen.Menu, keycode: Int) {
        val menuOptions = buildMenuOptions()
        val itemCount = menuOptions.options.size

        when (keycode) {
            19 /* DPAD_UP */ -> {
                val newIndex = if (screen.selectedIndex <= 0) itemCount - 1 else screen.selectedIndex - 1
                replaceTop(screen.copy(selectedIndex = newIndex))
            }
            20 /* DPAD_DOWN */ -> {
                val newIndex = if (screen.selectedIndex >= itemCount - 1) 0 else screen.selectedIndex + 1
                replaceTop(screen.copy(selectedIndex = newIndex))
            }
            21 /* DPAD_LEFT */ -> {
                // Change save slot left
                val newSlot = if (selectedSlotIndex.intValue <= 0) saveSlotManager.slots.size - 1 else selectedSlotIndex.intValue - 1
                selectedSlotIndex.intValue = newSlot
                refreshSlotInfo()
            }
            22 /* DPAD_RIGHT */ -> {
                // Change save slot right
                val newSlot = if (selectedSlotIndex.intValue >= saveSlotManager.slots.size - 1) 0 else selectedSlotIndex.intValue + 1
                selectedSlotIndex.intValue = newSlot
                refreshSlotInfo()
            }
            96 /* BUTTON_A - confirm */ -> {
                selectMenuItem(screen.selectedIndex)
            }
            97, 4 /* BUTTON_B, BACK - back/close */ -> {
                onClose?.invoke()
            }
        }
    }

    private var menuOptions: InGameMenuOptions? = null

    fun buildMenuOptions(): InGameMenuOptions {
        val opts = InGameMenuOptions(
            hasDiscs = bridge.getDiskCount() > 1,
            discLabel = "Disc ${bridge.getDiskIndex() + 1}",
            hasAchievements = bridge.supportsAchievements,
            hasGuides = false
        )
        menuOptions = opts
        return opts
    }

    private fun selectMenuItem(index: Int) {
        val opts = menuOptions ?: return
        when (index) {
            opts.resumeIndex -> onClose?.invoke()
            opts.saveStateIndex -> { saveState(); onClose?.invoke() }
            opts.loadStateIndex -> { loadState(); onClose?.invoke() }
            opts.settingsIndex -> onOpenNativeMenu?.invoke()
            opts.resetIndex -> { bridge.reset(); onClose?.invoke() }
            opts.quitIndex -> bridge.quit()
        }
    }
}
