package dev.cannoli.igm

import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class IGMController(
    val bridge: EmulatorBridge,
    val gameTitle: String,
    private val scope: CoroutineScope = MainScope(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val screenStack = mutableStateListOf<IGMScreen>()
    val currentScreen: IGMScreen? get() = screenStack.lastOrNull()
    val isOpen: Boolean get() = screenStack.isNotEmpty()

    var selectedSlotIndex = mutableIntStateOf(0)
    var slotThumbnail = mutableStateOf<Bitmap?>(null)
    var slotExists = mutableStateOf(false)
    var slotOccupied = mutableStateOf(emptyList<Boolean>())
    var undoLabel = mutableStateOf<String?>(null)
    val settingsItems = mutableStateOf<List<IGMSettingsItem>>(emptyList())

    private var inputTranslator = IgmInputTranslator(null)

    /** Supply the active Cannoli device mapping so raw host keycodes are normalized. */
    fun setInputMapping(mapping: IgmInputMapping?) {
        inputTranslator = IgmInputTranslator(mapping)
    }

    // Guide navigation is delegated to the shared GuideController. These pass-through getters
    // preserve the public API that ricotta/IGMOverlay.kt reads (controller.guideFiles.value,
    // controller.guideScrollDir.intValue, ...). cannoli-igm is a source dependency of the ricotta
    // fork; do not inline or rename these without updating ricotta.
    private val guideController = GuideController()
    val guideFiles get() = guideController.guideFiles
    val guidePageCount get() = guideController.guidePageCount
    val guideScrollDir get() = guideController.guideScrollDir
    val guideScrollXDir get() = guideController.guideScrollXDir
    val guidePageJump get() = guideController.guidePageJump
    val guidePageJumpDir get() = guideController.guidePageJumpDir
    val guideInitialScroll get() = guideController.guideInitialScroll
    val guideInitialScrollX get() = guideController.guideInitialScrollX

    private val saveSlotManager = SaveSlotManager()

    private var lastMenuIndex = 0

    fun openMenu() {
        val lastIndex = (buildMenuOptions().options.size - 1).coerceAtLeast(0)
        screenStack.clear()
        screenStack.add(IGMScreen.Menu(selectedIndex = lastMenuIndex.coerceIn(0, lastIndex)))
        refreshSlotInfo()
    }

    fun closeMenu() {
        rememberMenuIndex()
        screenStack.clear()
    }

    private fun rememberMenuIndex() {
        (screenStack.firstOrNull { it is IGMScreen.Menu } as? IGMScreen.Menu)?.let {
            lastMenuIndex = it.selectedIndex
        }
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

    private var occupancyCache: List<Boolean>? = null

    fun invalidateSlotCache() { occupancyCache = null }

    fun refreshSlotInfo() {
        val slot = saveSlotManager.slots[selectedSlotIndex.intValue]
        occupancyCache?.let { slotOccupied.value = it }
        scope.launch {
            val exists = withContext(io) { bridge.stateExists(slot.index) }
            slotExists.value = exists

            if (occupancyCache == null) {
                val occupancy = withContext(io) {
                    saveSlotManager.slots.map { bridge.stateExists(it.index) }
                }
                occupancyCache = occupancy
                slotOccupied.value = occupancy
            }

            slotThumbnail.value = withContext(io) { bridge.getStateThumbnail(slot.index) }
        }
    }

    fun saveState() {
        val slot = saveSlotManager.slots[selectedSlotIndex.intValue]
        saveSlotManager.saveState(bridge, slot)
        invalidateSlotCache()
        refreshSlotInfo()
    }

    fun loadState() {
        val slot = saveSlotManager.slots[selectedSlotIndex.intValue]
        saveSlotManager.loadState(bridge, slot)
        refreshSlotInfo()
    }

    fun suspendForNativeMenu() {
        rememberMenuIndex()
        bridge.setOnNativeMenuClosed { onNativeMenuClosed?.invoke() }
        bridge.openNativeMenu()
    }

    fun openAchievements() {
        push(IGMScreen.Achievements(achievements = bridge.getAchievements()))
    }

    private fun filteredAchievements(screen: IGMScreen.Achievements): List<AchievementInfo> = when (screen.filter) {
        1 -> screen.achievements.filter { it.unlocked }
        2 -> screen.achievements.filter { !it.unlocked }
        else -> screen.achievements
    }

    private fun achievementsHaveMix(list: List<AchievementInfo>): Boolean =
        list.any { it.unlocked } && list.any { !it.unlocked }

    private fun handleAchievementsKey(screen: IGMScreen.Achievements, keycode: Int) {
        val filtered = filteredAchievements(screen)
        val count = filtered.size
        when (keycode) {
            19 -> if (count > 0) replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count))
            20 -> if (count > 0) replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count))
            96 -> filtered.getOrNull(screen.selectedIndex)?.let {
                push(IGMScreen.AchievementDetail(achievement = it, parentIndex = screen.selectedIndex))
            }
            99 -> if (achievementsHaveMix(screen.achievements)) {
                replaceTop(screen.copy(filter = (screen.filter + 1) % 3, selectedIndex = 0))
            }
            97, 4 -> { pop(); if (screenStack.isEmpty()) onClose?.invoke() }
        }
    }

    private fun handleAchievementDetailKey(screen: IGMScreen.AchievementDetail, keycode: Int) {
        when (keycode) {
            97, 4 -> pop()
        }
    }

    fun attachGuides(manager: GuideManager) = guideController.attach(manager)

    fun onGuideScrollChanged(y: Int, x: Int) = guideController.onScrollChanged(y, x)

    fun openGuidePicker() {
        push(IGMScreen.GuidePicker())
    }

    private fun openGuide(guide: GuideFile) {
        val open = guideController.prepareGuide(guide) ?: return
        push(IGMScreen.Guide(filePath = open.filePath, page = open.initialPage, textZoom = open.textZoom))
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
            19 -> guideController.scroll(-1)
            20 -> guideController.scroll(1)
            21 -> if (type != GuideType.TXT && screen.textZoom > 1) guideController.scrollX(-1)
            22 -> if (type != GuideType.TXT && screen.textZoom > 1) guideController.scrollX(1)
            102 -> if (type == GuideType.PDF) {
                replaceTop(screen.copy(page = (screen.page - 1).coerceAtLeast(0)))
            } else guideController.pageJump(-1)
            103 -> if (type == GuideType.PDF) {
                replaceTop(screen.copy(page = (screen.page + 1).coerceAtMost(guidePageCount.intValue - 1)))
            } else guideController.pageJump(1)
            100 -> {
                guideController.beginZoomReseed()
                replaceTop(screen.copy(textZoom = if (screen.textZoom >= GuideZoom.levels) 1 else screen.textZoom + 1))
            }
            97, 4 -> {
                guideController.saveGuide(guide, if (type == GuideType.PDF) screen.page else null, screen.textZoom)
                guideController.scroll(0)
                guideController.scrollX(0)
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

    var onNativeMenuClosed: (() -> Unit)? = null

    /**
     * Handle a key event from the gamepad.
     * Android keycodes: DPAD_UP=19, DPAD_DOWN=20, DPAD_LEFT=21, DPAD_RIGHT=22,
     * BUTTON_A=96, BUTTON_B=97, BACK=4
     */
    fun handleKeyDown(keycode: Int) {
        val screen = currentScreen ?: return
        val normalized = inputTranslator.normalize(keycode)

        when (screen) {
            is IGMScreen.Menu -> handleMenuKey(screen, normalized)
            is IGMScreen.GuidePicker -> handleGuidePickerKey(screen, normalized)
            is IGMScreen.Guide -> handleGuideKey(screen, normalized)
            is IGMScreen.Achievements -> handleAchievementsKey(screen, normalized)
            is IGMScreen.AchievementDetail -> handleAchievementDetailKey(screen, normalized)
            is IGMScreen.ProviderSettings -> handleProviderKey(normalized)
            is IGMScreen.SettingsExitPrompt -> handleProviderKey(normalized)
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

    private var providerNav: ProviderSettingsController? = null

    fun openProviderSettings() {
        val provider = bridge.settingsProvider() ?: return
        val nav = ProviderSettingsController(provider)
        providerNav = nav
        nav.setOnChanged { renderProviderState(nav.state()) }
        renderProviderState(nav.enter())
    }

    private fun renderProviderState(state: ProviderSettingsController.State) {
        when (state) {
            is ProviderSettingsController.State.Menu -> {
                val screen = IGMScreen.ProviderSettings(state.selectedIndex, state.path, state.title)
                if (currentScreen is IGMScreen.ProviderSettings || currentScreen is IGMScreen.SettingsExitPrompt) {
                    replaceTop(screen)
                } else {
                    push(screen)
                }
                settingsItems.value = state.items.map(::toProviderRenderItem)
            }
            is ProviderSettingsController.State.Prompt -> {
                replaceTop(IGMScreen.SettingsExitPrompt(state.selectedIndex))
                settingsItems.value = state.options.map { IGMSettingsItem(it) }
            }
            is ProviderSettingsController.State.Closed -> {
                providerNav = null
                if (currentScreen is IGMScreen.ProviderSettings || currentScreen is IGMScreen.SettingsExitPrompt) pop()
            }
            is ProviderSettingsController.State.ActionFired -> { /* activate() pushed its own screen (or nothing); leave the stack untouched */ }
        }
    }

    private fun toProviderRenderItem(item: GenericIgmSettingsItem): IGMSettingsItem = when (item) {
        is GenericIgmSettingsItem.Category -> IGMSettingsItem(item.label)
        is GenericIgmSettingsItem.Action -> IGMSettingsItem(item.label)
        is GenericIgmSettingsItem.Choice -> IGMSettingsItem(item.label, item.value, item.hint)
    }

    private fun handleProviderKey(keycode: Int) {
        val nav = providerNav ?: return
        val button = when (keycode) {
            19 -> ProviderSettingsController.Nav.UP
            20 -> ProviderSettingsController.Nav.DOWN
            21 -> ProviderSettingsController.Nav.LEFT
            22 -> ProviderSettingsController.Nav.RIGHT
            96 -> ProviderSettingsController.Nav.CONFIRM
            97, 4 -> ProviderSettingsController.Nav.BACK
            else -> return
        }
        renderProviderState(nav.onNav(button))
    }

    private fun selectMenuItem(index: Int) {
        val opts = menuOptions ?: return
        when (opts.actionAt(index)) {
            IgmMenuAction.RESUME -> onClose?.invoke()
            IgmMenuAction.SAVE_STATE -> { saveState(); onClose?.invoke() }
            IgmMenuAction.LOAD_STATE -> { loadState(); onClose?.invoke() }
            IgmMenuAction.SETTINGS -> if (bridge.settingsProvider() != null) {
                openProviderSettings()
            } else {
                onOpenNativeMenu?.invoke()
            }
            IgmMenuAction.RESET -> { bridge.reset(); onClose?.invoke() }
            IgmMenuAction.QUIT -> { onClose?.invoke(); bridge.quit() }
            IgmMenuAction.GUIDE -> {
                if (guideFiles.value.size == 1) openGuide(guideFiles.value[0]) else openGuidePicker()
            }
            IgmMenuAction.ACHIEVEMENTS -> openAchievements()
            IgmMenuAction.SWITCH_DISC, IgmMenuAction.REASSIGN, IgmMenuAction.CHEATS, null -> {}
        }
    }
}
