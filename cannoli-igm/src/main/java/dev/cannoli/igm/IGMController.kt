package dev.cannoli.igm

import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.io.File

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

    private var guideManager: GuideManager? = null
    var guideFiles = mutableStateOf<List<GuideFile>>(emptyList())
    var guidePageCount = mutableIntStateOf(0)
    var guideScrollDir = mutableIntStateOf(0)
    var guideScrollXDir = mutableIntStateOf(0)
    var guidePageJump = mutableIntStateOf(0)
    var guidePageJumpDir = mutableIntStateOf(0)
    var guideInitialScroll = mutableIntStateOf(0)
    var guideInitialScrollX = mutableIntStateOf(0)
    private var guideScrollPos = 0
    private var guideScrollXPos = 0

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
        push(IGMScreen.Achievements(achievements = bridge.getAchievements()))
    }

    private fun filteredAchievements(screen: IGMScreen.Achievements): List<AchievementInfo> = when (screen.filter) {
        1 -> screen.achievements.filter { it.unlocked }
        2 -> screen.achievements.filter { !it.unlocked }
        else -> screen.achievements
    }

    private fun handleAchievementsKey(screen: IGMScreen.Achievements, keycode: Int) {
        val filtered = filteredAchievements(screen)
        val count = filtered.size
        when (keycode) {
            19 -> if (count > 0) replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count))
            20 -> if (count > 0) replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count))
            96 -> filtered.getOrNull(screen.selectedIndex)?.let {
                push(IGMScreen.AchievementDetail(achievement = it, parentIndex = screen.selectedIndex))
            }
            99 -> replaceTop(screen.copy(filter = (screen.filter + 1) % 3, selectedIndex = 0))
            97, 4 -> { pop(); if (screenStack.isEmpty()) onClose?.invoke() }
        }
    }

    private fun handleAchievementDetailKey(screen: IGMScreen.AchievementDetail, keycode: Int) {
        when (keycode) {
            97, 4 -> pop()
        }
    }

    fun attachGuides(manager: GuideManager) {
        guideManager = manager
        guideFiles.value = manager.findGuides()
    }

    fun onGuideScrollChanged(y: Int, x: Int) {
        guideScrollPos = y
        guideScrollXPos = x
    }

    fun openGuidePicker() {
        push(IGMScreen.GuidePicker())
    }

    private fun openGuide(guide: GuideFile) {
        val manager = guideManager ?: return
        val saved = manager.loadSavedPosition(guide.file)
        guideScrollDir.intValue = 0
        guideScrollXDir.intValue = 0
        guidePageJump.intValue = 0
        guideScrollXPos = saved.scrollX
        guideInitialScrollX.intValue = saved.scrollX
        guidePageCount.intValue = if (guide.type == GuideType.PDF) {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(
                    guide.file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                pfd.use { android.graphics.pdf.PdfRenderer(it).use { r -> r.pageCount } }
            } catch (_: Exception) { 1 }
        } else 0
        guideScrollPos = if (guide.type == GuideType.PDF) saved.scrollY else saved.position
        guideInitialScroll.intValue = guideScrollPos
        if (guide.type == GuideType.PDF) {
            push(IGMScreen.Guide(
                filePath = guide.file.absolutePath,
                page = saved.position.coerceIn(0, (guidePageCount.intValue - 1).coerceAtLeast(0)),
                textZoom = saved.zoom
            ))
        } else {
            push(IGMScreen.Guide(filePath = guide.file.absolutePath, textZoom = saved.zoom))
        }
    }

    private fun handleGuidePickerKey(screen: IGMScreen.GuidePicker, keycode: Int) {
        val count = guideFiles.value.size
        if (count == 0) { pop(); if (screenStack.isEmpty()) onClose?.invoke(); return }
        when (keycode) {
            19 -> replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count))
            20 -> replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count))
            96 -> guideFiles.value.getOrNull(screen.selectedIndex)?.let { openGuide(it) }
            97, 4 -> { pop(); if (screenStack.isEmpty()) onClose?.invoke() }
        }
    }

    private fun handleGuideKey(screen: IGMScreen.Guide, keycode: Int) {
        val guide = guideFiles.value.firstOrNull { it.file.absolutePath == screen.filePath } ?: return
        val type = guide.type
        when (keycode) {
            19 -> guideScrollDir.intValue = -1
            20 -> guideScrollDir.intValue = 1
            21 -> if (type != GuideType.TXT && screen.textZoom > 1) guideScrollXDir.intValue = -1
            22 -> if (type != GuideType.TXT && screen.textZoom > 1) guideScrollXDir.intValue = 1
            102 -> if (type == GuideType.PDF) {
                replaceTop(screen.copy(page = (screen.page - 1).coerceAtLeast(0)))
            } else { guidePageJumpDir.intValue = -1; guidePageJump.intValue++ }
            103 -> if (type == GuideType.PDF) {
                replaceTop(screen.copy(page = (screen.page + 1).coerceAtMost(guidePageCount.intValue - 1)))
            } else { guidePageJumpDir.intValue = 1; guidePageJump.intValue++ }
            100 -> {
                guideInitialScroll.intValue = guideScrollPos
                guideInitialScrollX.intValue = guideScrollXPos
                replaceTop(screen.copy(textZoom = if (screen.textZoom >= 3) 1 else screen.textZoom + 1))
            }
            97, 4 -> {
                val pos = if (type == GuideType.PDF) screen.page else guideScrollPos
                guideManager?.save(guide.file, pos, guideScrollPos, guideScrollXPos, screen.textZoom)
                guideScrollDir.intValue = 0
                guideScrollXDir.intValue = 0
                pop()
                if (screenStack.isEmpty()) onClose?.invoke()
            }
        }
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
            is IGMScreen.GuidePicker -> handleGuidePickerKey(screen, keycode)
            is IGMScreen.Guide -> handleGuideKey(screen, keycode)
            is IGMScreen.Achievements -> handleAchievementsKey(screen, keycode)
            is IGMScreen.AchievementDetail -> handleAchievementDetailKey(screen, keycode)
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
            hasGuides = guideFiles.value.isNotEmpty()
        )
        menuOptions = opts
        return opts
    }

    private fun selectMenuItem(index: Int) {
        val opts = menuOptions ?: return
        when (opts.actionAt(index)) {
            IgmMenuAction.RESUME -> onClose?.invoke()
            IgmMenuAction.SAVE_STATE -> { saveState(); onClose?.invoke() }
            IgmMenuAction.LOAD_STATE -> { loadState(); onClose?.invoke() }
            IgmMenuAction.SETTINGS -> onOpenNativeMenu?.invoke()
            IgmMenuAction.RESET -> { bridge.reset(); onClose?.invoke() }
            IgmMenuAction.QUIT -> { onClose?.invoke(); bridge.quit() }
            IgmMenuAction.GUIDE -> {
                if (guideFiles.value.size == 1) openGuide(guideFiles.value[0]) else openGuidePicker()
            }
            IgmMenuAction.ACHIEVEMENTS -> openAchievements()
            IgmMenuAction.SWITCH_DISC, IgmMenuAction.REASSIGN, null -> {}
        }
    }
}
