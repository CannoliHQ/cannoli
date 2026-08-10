package dev.cannoli.igm

import android.graphics.Bitmap
import dev.cannoli.core.SaveSlotStore
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IGMController(
    val bridge: RetroArchBridge,
    val gameTitle: String,
    private val slots: SaveSlotStore,
    private val scope: CoroutineScope = MainScope(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val screenStack = mutableStateListOf<IGMScreen>()
    val currentScreen: IGMScreen? get() = screenStack.lastOrNull()
    val isOpen: Boolean get() = screenStack.isNotEmpty()

    var selectedSlotIndex = mutableIntStateOf(0)
    var slotThumbnail = mutableStateOf<Bitmap?>(null)
    var slotThumbnailLoaded = mutableStateOf(false)
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

    data class CheatItem(val label: String, val enabled: Boolean, val supported: Boolean)

    /** Every row in the loaded file, whatever the filter shows. */
    val cheatItems = mutableStateOf<List<CheatItem>>(emptyList())

    /** The rows the filter lets through, in the order the screen draws them. */
    val cheatVisibleItems = mutableStateOf<List<CheatItem>>(emptyList())
    val cheatFilter = mutableStateOf(CheatFilter.ALL)
    val cheatHasRemembered = mutableStateOf(false)

    /** Fired with the number of cheats reapplied, for the host's OSD. */
    var onCheatsRestored: ((Int) -> Unit)? = null

    private var cheatManager: CheatManager? = null
    private var cheatFile: CheatFile? = null
    private var cheatSession: CheatSession? = null
    // Session row indexes in display order, the map between what the screen shows and what
    // RetroArch was told about. A filtered position is never a cheat index.
    private var visibleCheatRows: List<Int> = emptyList()
    private var cheatHardcoreWarned = false
    private var cheatLoadPending = false
    private var outstandingCheatLoads = 0
    private var staleCheatSnapshots = 0
    private var pendingCheatRestore: Set<String> = emptySet()

    fun attachCheats(manager: CheatManager) {
        cheatManager = manager
        cheatFile = manager.findCheatFile()
        bridge.setOnCheatsLoaded(::onCheatsLoaded)
        // RetroArch drains the queue on the runloop, which does not run while the menu is up, so a
        // load asked for on the way into the screen is never answered. Asking here is what puts the
        // data in hand during gameplay, before the first open.
        loadCheatFile()
    }

    fun openCheats() {
        // The menu only offers the row when a session is held, so the screen never opens empty.
        val session = cheatSession ?: return
        push(IGMScreen.Cheats(initialCheatSelection(session)))
    }

    // A load can be dropped, or answered with nothing, and its snapshot is the only thing that
    // would say so. Opening the menu is the retry: it is the last moment the game is still running.
    private fun requestCheatsIfMissing() {
        if (cheatFile == null || cheatSession != null) return
        loadCheatFile()
    }

    private fun loadCheatFile() {
        val file = cheatFile ?: return
        cheatSession = null
        // With no session there is nothing to draw, and rendering that is the same reset every
        // other path uses. Clearing by hand here is what let the rows outlive the file.
        renderCheats()
        cheatLoadPending = true
        outstandingCheatLoads++
        bridge.loadCheatFile(file.file.absolutePath)
    }

    private fun onCheatsLoaded(observed: List<RetroArchBridge.CheatRow>) {
        if (outstandingCheatLoads > 0) outstandingCheatLoads--
        // Requested back when another file was open. Its rows would be matched against this file
        // and every one of them would come back unsupported, and the snapshot that really belongs
        // here would then be turned away as a repeat.
        if (staleCheatSnapshots > 0) {
            staleCheatSnapshots--
            return
        }
        // Only the snapshot this session is still waiting for. A repeat of one already taken would
        // rebuild the rows from the emulator's post-load state and drop what the user has since
        // turned on; one from before a disc switch describes content that is gone.
        if (!cheatLoadPending) return
        val manager = cheatManager ?: return
        val file = cheatFile ?: return
        // RetroArch emits a snapshot whether the load worked or not, and a failed one carries no
        // rows. Taking it would hold an empty session for the rest of the game, so it is not taken.
        // The token goes back only when nothing is queued behind this answer, which is what keeps a
        // refused file from latching the screen while still letting a better snapshot through.
        if (observed.isEmpty()) {
            if (outstandingCheatLoads == 0) cheatLoadPending = false
            return
        }
        cheatLoadPending = false
        // Where the selection is now, read before the new rows land. A snapshot arriving mid-visit
        // is not an entry: the screen keeps the row the user is holding, and only opening it fresh
        // gets to choose where to start.
        val open = currentScreen as? IGMScreen.Cheats
        val anchor = open?.let(::cheatAnchorOf)
        val session = CheatSession(manager, file, observed)
        cheatSession = session
        // A disc switch reinitializes content state, so the set the user had on is put back by
        // identity rather than assumed to have survived.
        if (pendingCheatRestore.isNotEmpty()) {
            val restored = session.restore(pendingCheatRestore)
            pendingCheatRestore = emptySet()
            for (row in restored) bridge.toggleCheat(row.raIndex)
            if (restored.isNotEmpty()) bridge.applyCheats()
        }
        renderCheats()
        if (open != null) placeCheatSelection(open, anchor)
    }

    // A row RetroArch did not take cannot be toggled, so starting on one would offer an action that
    // silently does nothing. The restore offer takes the selection whenever it exists, since putting
    // the last set back is the likeliest first thing to want; otherwise the first row that can
    // actually be toggled, and a screen with nothing actionable starts on nothing at all.
    private fun initialCheatSelection(session: CheatSession): Int {
        if (cheatRestoreRows() == 1) return 0
        val supported = visibleCheatRows.indexOfFirst { session.rows[it].supported }
        return if (supported >= 0) supported else -1
    }

    private fun renderCheats() {
        val session = cheatSession
        if (session == null) {
            cheatItems.value = emptyList()
            cheatVisibleItems.value = emptyList()
            visibleCheatRows = emptyList()
            cheatHasRemembered.value = false
            return
        }
        val items = session.rows.map {
            CheatItem(it.label, session.isEnabled(it), it.supported)
        }
        cheatItems.value = items
        visibleCheatRows = items.indices.filter { cheatFilter.value.shows(items[it].enabled) }
        cheatVisibleItems.value = visibleCheatRows.map { items[it] }
        val remembered = cheatManager?.loadLastUsed()
        cheatHasRemembered.value = remembered != null &&
            remembered.fileName == session.file.file.name &&
            session.canRestore(remembered.hashes)
    }

    private fun cycleCheatFilter(screen: IGMScreen.Cheats) {
        val anchor = cheatAnchorOf(screen)
        cheatFilter.value = when (cheatFilter.value) {
            CheatFilter.ALL -> CheatFilter.ON
            CheatFilter.ON -> CheatFilter.OFF
            CheatFilter.OFF -> CheatFilter.ALL
        }
        renderCheats()
        placeCheatSelection(screen, anchor)
    }

    // The screen draws the restore offer when it has work, then the filtered cheats. This and
    // selectedCheatRow are the only places that know the shape; nothing else counts rows.
    private fun cheatRestoreRows(): Int = if (cheatHasRemembered.value) 1 else 0

    /** The session row the selection sits on, or null when it is on a row that is not a cheat. */
    private fun selectedCheatRow(screen: IGMScreen.Cheats): Int? =
        visibleCheatRows.getOrNull(screen.selectedIndex - cheatRestoreRows())

    /** What the selection is on, which is what it goes back to once the rows have moved. */
    private sealed interface CheatAnchor {
        data object Restore : CheatAnchor
        data class Row(val index: Int) : CheatAnchor
    }

    /** Null when the selection is on nothing, which is a screen with no actionable row. */
    private fun cheatAnchorOf(screen: IGMScreen.Cheats): CheatAnchor? = when {
        cheatRestoreRows() == 1 && screen.selectedIndex == 0 -> CheatAnchor.Restore
        else -> selectedCheatRow(screen)?.let(CheatAnchor::Row)
    }

    // The restore offer comes and goes above the cheats, so a remembered index is not a remembered
    // row. A cheat the new view hides leaves the position behind instead, which keeps a walk down
    // the list going, and a screen with nothing on it holds no selection at all.
    private fun placeCheatSelection(screen: IGMScreen.Cheats, anchor: CheatAnchor?) {
        val head = cheatRestoreRows()
        val last = head + visibleCheatRows.size - 1
        val index = when {
            last < 0 -> -1
            anchor == CheatAnchor.Restore && head == 1 -> 0
            anchor is CheatAnchor.Row -> visibleCheatRows.indexOf(anchor.index)
                .let { if (it >= 0) it + head else screen.selectedIndex.coerceIn(0, last) }
            else -> screen.selectedIndex.coerceIn(0, last)
        }
        replaceTop(screen.copy(selectedIndex = index))
    }

    private fun toggleCheatRow(rowIndex: Int) {
        val session = cheatSession ?: return
        val row = session.toggle(rowIndex) ?: return
        bridge.toggleCheat(row.raIndex)
        renderCheats()
        // Turning a cheat on or off can take it out of the view that is filtering on that very
        // state, so the selection is placed again rather than left on a row that moved.
        (currentScreen as? IGMScreen.Cheats)?.let { placeCheatSelection(it, CheatAnchor.Row(rowIndex)) }
    }

    // Only one file is active at a time, so a set remembered from a different one describes rows
    // that are not loaded. Matching it by identity against whatever is open would leak that file's
    // choices into this one; the store's file name is what says whether it belongs here.
    private fun reapplyLastUsedCheats() {
        val session = cheatSession ?: return
        val remembered = cheatManager?.loadLastUsed() ?: return
        if (remembered.fileName != session.file.file.name) return
        val screen = currentScreen as? IGMScreen.Cheats
        val anchor = screen?.let(::cheatAnchorOf)
        val restored = session.restore(remembered.hashes)
        if (restored.isEmpty()) return
        for (row in restored) bridge.toggleCheat(row.raIndex)
        bridge.applyCheats()
        renderCheats()
        // Everything just turned on, which under a filter on that state can empty the view out from
        // under the selection.
        if (screen != null) placeCheatSelection(screen, anchor)
        onCheatsRestored?.invoke(restored.size)
    }

    private fun handleCheatsKey(screen: IGMScreen.Cheats, keycode: Int) {
        val restoreRows = cheatRestoreRows()
        val count = restoreRows + cheatVisibleItems.value.size
        val onRestoreRow = restoreRows == 1 && screen.selectedIndex == 0
        val navigates = keycode == 19 || keycode == 20 || keycode == 97 || keycode == 4
        // Between a queued load and its snapshot these rows are not the list the emulator holds. A
        // toggle sent now targets the old list and the bridge drops it as out of range, which would
        // leave a row reading enabled that is not. Moving and leaving stay live.
        if (cheatLoadPending && !navigates) return
        when (keycode) {
            19 -> if (count > 0) replaceTop(
                screen.copy(
                    selectedIndex = if (screen.selectedIndex < 0) count - 1
                    else ((screen.selectedIndex - 1) + count) % count
                )
            )
            20 -> if (count > 0) replaceTop(
                screen.copy(
                    selectedIndex = if (screen.selectedIndex < 0) 0
                    else (screen.selectedIndex + 1) % count
                )
            )
            96 -> if (onRestoreRow) {
                reapplyLastUsedCheats()
            } else {
                val rowIndex = selectedCheatRow(screen)
                if (rowIndex != null) {
                    if (needsHardcoreWarning(rowIndex)) {
                        push(IGMScreen.CheatsHardcoreWarning(pendingRowIndex = rowIndex))
                    } else {
                        toggleCheatRow(rowIndex)
                    }
                }
            }
            99 -> if (cheatItems.value.isNotEmpty()) cycleCheatFilter(screen)
            97, 4 -> { pop(); if (screenStack.isEmpty()) onClose?.invoke() }
        }
    }

    // Enabling any cheat makes RetroArch pause hardcore achievements, so the first enable of the
    // session asks first. Turning one off costs nothing and never asks.
    private fun needsHardcoreWarning(rowIndex: Int): Boolean {
        if (cheatHardcoreWarned || !bridge.hardcoreActive) return false
        val session = cheatSession ?: return false
        val row = session.rows.getOrNull(rowIndex) ?: return false
        return row.supported && !session.isEnabled(row)
    }

    private fun handleCheatsHardcoreWarningKey(screen: IGMScreen.CheatsHardcoreWarning, keycode: Int) {
        when (keycode) {
            96 -> {
                cheatHardcoreWarned = true
                pop()
                toggleCheatRow(screen.pendingRowIndex)
            }
            97, 4 -> pop()
        }
    }

    private var lastMenuIndex = 0
    private var lastMenuAction: IgmMenuAction? = null

    // Cheevos load once per session and never drop back to empty, so a positive read is frozen;
    // a still-zero read is retried on the next open in case the set was still loading (login,
    // network fetch) the first time this was asked. bridge.getAchievements() builds the full list
    // from a native snapshot, so this keeps that call bounded to once per menu open instead of once
    // per keypress, since buildMenuOptions() runs on every D-pad move inside the menu.
    private var achievementCount = 0

    private fun refreshAchievementCount() {
        if (achievementCount > 0 || !bridge.supportsAchievements) return
        achievementCount = bridge.getAchievements().size
    }

    fun openMenu() {
        refreshDiskInfo()
        requestCheatsIfMissing()
        refreshAchievementCount()
        val options = buildMenuOptions()
        val lastIndex = (options.actions.size - 1).coerceAtLeast(0)
        // Rows come and go between opens now that the cheats snapshot lands during gameplay, so a
        // remembered index would point at whatever moved into its place. The row the user left on
        // is what comes back; its index is only the fallback for a row that is no longer there.
        val selected = lastMenuAction
            ?.let { options.actions.indexOf(it) }
            ?.takeIf { it >= 0 }
            ?: lastMenuIndex.coerceIn(0, lastIndex)
        screenStack.clear()
        screenStack.add(IGMScreen.Menu(selectedIndex = selected))
        refreshSlotInfo()
    }

    fun closeMenu() {
        rememberMenuIndex()
        screenStack.clear()
    }

    private fun rememberMenuIndex() {
        (screenStack.firstOrNull { it is IGMScreen.Menu } as? IGMScreen.Menu)?.let {
            lastMenuIndex = it.selectedIndex
            lastMenuAction = buildMenuOptions().actionAt(it.selectedIndex)
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

    // What has been read off disk, and whether it is still believed. The slots only change when
    // this process writes them, so a read is needed on the first look and after a write, never
    // just because the menu opened again.
    private var loadedSlot = -1
    private var slotsDirty = true
    private var slotLoadToken = 0

    fun invalidateSlotCache() {
        slotsDirty = true
    }

    fun refreshSlotInfo() {
        val slot = selectedSlotIndex.intValue
        val slotChanged = loadedSlot != slot
        if (!slotChanged && !slotsDirty) return

        val token = ++slotLoadToken
        // Another slot's thumbnail is wrong rather than merely old, so it goes at once. The same
        // slot keeps its image until the new one lands, which reads as a swap rather than a blank.
        if (slotChanged) {
            slotThumbnail.value = null
            slotThumbnailLoaded.value = false
        }
        scope.launch {
            val exists = withContext(io) { slots.exists(slot) }
            if (token != slotLoadToken) return@launch
            slotExists.value = exists

            if (slotsDirty || slotOccupied.value.isEmpty()) {
                val occupancy = withContext(io) { slots.occupancy() }
                if (token != slotLoadToken) return@launch
                slotOccupied.value = occupancy
            }

            val thumbnail = withContext(io) { slots.thumbnail(slot) }
            if (token != slotLoadToken) return@launch
            slotThumbnail.value = thumbnail
            slotThumbnailLoaded.value = true
            loadedSlot = slot
            slotsDirty = false
        }
    }

    fun saveState() {
        val slot = selectedSlotIndex.intValue
        // The auto slot holds one state, so the one it is about to lose is archived first. This
        // has to finish before the write is queued, or it would archive the new state instead.
        if (slot == SaveSlotStore.AUTO_SLOT) slots.rotateAutoIntoHistory()
        bridge.saveState(slot)
        // Marked stale but deliberately not read here. Between the rotation above and the write
        // the emulator has only queued, the slot is a hole on disk, so reading now reports an
        // empty slot and then corrects itself. onStateWritten does the reading, once there is
        // something to read; this flag is what makes the next open re-read if that never arrives.
        invalidateSlotCache()
    }

    /**
     * The emulator finished writing a slot, which the save request itself only queued.
     *
     * Saving closes the menu, so by the time this arrives the menu is usually shut. Reading anyway
     * is the point: the window keeps the last frame it drew, so leaving the stale thumbnail in
     * place until the next open would show the previous screenshot before swapping to this one.
     */
    fun onStateWritten() {
        invalidateSlotCache()
        refreshSlotInfo()
    }

    fun loadState() {
        bridge.loadState(selectedSlotIndex.intValue)
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
        1 -> screen.achievements.filter { it.unlocked }.sortedByUnlockedNewestFirst()
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

    val slotCount get() = slots.slotCount

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
            is IGMScreen.Cheats -> handleCheatsKey(screen, normalized)
            is IGMScreen.CheatsHardcoreWarning -> handleCheatsHardcoreWarningKey(screen, normalized)
            is IGMScreen.Achievements -> handleAchievementsKey(screen, normalized)
            is IGMScreen.AchievementDetail -> handleAchievementDetailKey(screen, normalized)
            is IGMScreen.ProviderSettings -> handleProviderKey(normalized)
            is IGMScreen.SettingsExitPrompt -> handleProviderKey(normalized)
        }
    }

    private fun handleMenuKey(screen: IGMScreen.Menu, keycode: Int) {
        val menuOptions = buildMenuOptions()
        val itemCount = menuOptions.actions.size

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
                if (menuOptions.actionAt(screen.selectedIndex) == IgmMenuAction.SWITCH_DISC) {
                    cycleDisc(-1)
                    stayOnDiscRow(screen)
                } else {
                    // Change save slot left
                    val newSlot = if (selectedSlotIndex.intValue <= 0) slots.slotCount - 1 else selectedSlotIndex.intValue - 1
                    selectedSlotIndex.intValue = newSlot
                    refreshSlotInfo()
                }
            }
            22 /* DPAD_RIGHT */ -> {
                if (menuOptions.actionAt(screen.selectedIndex) == IgmMenuAction.SWITCH_DISC) {
                    cycleDisc(1)
                    stayOnDiscRow(screen)
                } else {
                    // Change save slot right
                    val newSlot = if (selectedSlotIndex.intValue >= slots.slotCount - 1) 0 else selectedSlotIndex.intValue + 1
                    selectedSlotIndex.intValue = newSlot
                    refreshSlotInfo()
                }
            }
            96 /* BUTTON_A - confirm */ -> {
                selectMenuItem(screen.selectedIndex)
            }
            97, 4 /* BUTTON_B, BACK - back/close */ -> {
                onClose?.invoke()
            }
        }
    }

    // Switching disc drops the cheat session until the new disc's snapshot lands, which can take the
    // Cheats row out of the menu from under the selection. The row being held is the disc row, not
    // the index it happened to have.
    private fun stayOnDiscRow(screen: IGMScreen.Menu) {
        val index = buildMenuOptions().switchDiscIndex
        if (index >= 0 && index != screen.selectedIndex) {
            replaceTop(screen.copy(selectedIndex = index))
        }
    }

    private var menuOptions: InGameMenuOptions? = null

    private var diskCount = 0
    val currentDiskIndex = mutableIntStateOf(0)

    fun refreshDiskInfo() {
        diskCount = bridge.getDiskCount()
        currentDiskIndex.intValue = bridge.getDiskIndex()
    }

    // The set is queued onto the emulator's own thread, so the index is moved here rather than
    // read back, and refreshDiskInfo corrects it on the next open.
    private fun cycleDisc(direction: Int) {
        if (diskCount <= 1) return
        val next = ((currentDiskIndex.intValue + direction) + diskCount) % diskCount
        if (next == currentDiskIndex.intValue) return
        bridge.setDiskIndex(next)
        currentDiskIndex.intValue = next
        invalidateCheatsForDisc()
    }

    // The new disc may reinitialize content state, so what RetroArch holds is no longer known to
    // match. The reload puts this session's set back by identity; a snapshot still in flight
    // describes the disc that just left, so it is no longer wanted either.
    private fun invalidateCheatsForDisc() {
        // Only a live session has anything to say about what was on. The second switch in a row
        // finds none, and must leave the first switch's capture alone rather than blank it.
        cheatSession?.let { pendingCheatRestore = it.enabledHashes() }
        cheatSession = null
        cheatLoadPending = false
        staleCheatSnapshots = outstandingCheatLoads
        loadCheatFile()
    }

    fun buildMenuOptions(): InGameMenuOptions {
        val opts = InGameMenuOptions(
            hasDiscs = diskCount > 1,
            discIndex = currentDiskIndex.intValue,
            hasAchievements = bridge.supportsAchievements && achievementCount > 0,
            hasGuides = guideFiles.value.isNotEmpty(),
            hasCheats = cheatSession?.rows?.isNotEmpty() == true,
            hasSaveStates = bridge.savestatesAllowed,
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
            IgmMenuAction.SETTINGS -> openProviderSettings()
            IgmMenuAction.RESET -> { bridge.reset(); onClose?.invoke() }
            IgmMenuAction.QUIT -> {
                // RetroArch writes the auto slot itself while shutting down, so the state it is
                // about to replace has to be archived before the quit is queued.
                if (bridge.savesOnQuit) slots.rotateAutoIntoHistory()
                onClose?.invoke()
                bridge.quit()
            }
            IgmMenuAction.GUIDE -> {
                if (guideFiles.value.size == 1) openGuide(guideFiles.value[0]) else openGuidePicker()
            }
            IgmMenuAction.ACHIEVEMENTS -> openAchievements()
            IgmMenuAction.CHEATS -> openCheats()
            IgmMenuAction.SWITCH_DISC, IgmMenuAction.REASSIGN, null -> {}
        }
    }
}
