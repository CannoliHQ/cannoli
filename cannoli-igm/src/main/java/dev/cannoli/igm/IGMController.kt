package dev.cannoli.igm

import android.graphics.Bitmap
import dev.cannoli.core.SaveSlotStore
import dev.cannoli.ui.components.Direction
import dev.cannoli.ui.components.KeyboardController
import dev.cannoli.ui.components.KeyboardPress
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHADER_SAVE_SEGMENT = "save"

private val FILENAME_RESERVED = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

enum class UndoAction { SAVE, LOAD }

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
    var undoAction = mutableStateOf<UndoAction?>(null)
    val settingsItems = mutableStateOf<List<IGMSettingsItem>>(emptyList())

    val settingsCanRestore = mutableStateOf(false)

    val settingsCanReset = mutableStateOf(false)

    val settingsCanReorder = mutableStateOf(false)

    val settingsCanRemovePass = mutableStateOf(false)

    val settingsReordering = mutableStateOf(false)

    private var inputTranslator = IgmInputTranslator(null)

    fun setInputMapping(mapping: IgmInputMapping?) {
        inputTranslator = IgmInputTranslator(mapping)
        inputMapping = mapping
        menuGlyph.value = menuGlyphFor(inputMapping, menuShortcutTable)
    }

    private var inputMapping: IgmInputMapping? = null
    private var menuShortcutTable: Map<ShortcutAction, Set<Int>> = emptyMap()

    val menuGlyph = mutableStateOf(dev.cannoli.ui.theme.MenuGlyph.Menu)

    fun isMenuKey(rawKeycode: Int): Boolean = inputTranslator.isMenuKey(rawKeycode)

    private val menuShortcuts = MenuShortcutGate()

    internal var clock: () -> Long = { System.currentTimeMillis() }

    internal var scheduleMenuHold: (delayMs: Long, block: () -> Unit) -> Unit = { delayMs, block ->
        scope.launch { delay(delayMs); block() }
    }

    fun setMenuShortcuts(shortcuts: Map<ShortcutAction, Set<Int>>) {
        menuShortcuts.setShortcuts(shortcuts)
        menuShortcutTable = shortcuts
        menuGlyph.value = menuGlyphFor(inputMapping, menuShortcutTable)
    }

    val overlayPicker = PreviewPickerController()

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

    val cheatItems = mutableStateOf<List<CheatItem>>(emptyList())

    val cheatVisibleItems = mutableStateOf<List<CheatItem>>(emptyList())
    val players = mutableStateOf<List<PlayerSlot>>(emptyList())
    val cheatFilter = mutableStateOf(CheatFilter.ALL)
    val cheatHasRemembered = mutableStateOf(false)

    var onCheatsRestored: ((Int) -> Unit)? = null

    private var cheatManager: CheatManager? = null
    private var cheatFile: CheatFile? = null
    private var cheatSession: CheatSession? = null
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
        loadCheatFile()
    }

    val binding: BindingController = BindingController()

    private val heldPastCapture = mutableSetOf<Int>()

    fun openShortcuts() {
        binding.onProgress = { keys, elapsed ->
            (currentScreen as? IGMScreen.Shortcuts)?.let {
                replaceTop(it.copy(heldKeys = keys, countdownMs = elapsed))
            }
        }
        binding.onCommit = { chord ->
            (currentScreen as? IGMScreen.Shortcuts)?.let { screen ->
                ShortcutAction.entries.getOrNull(screen.selectedIndex)?.let { action ->
                    stagedShortcut(action) { bridge.setShortcutBinding(action, chord) }
                }
                heldPastCapture.addAll(chord)
                replaceTop(screen.copy(listening = false, heldKeys = emptySet(), countdownMs = 0))
            }
        }
        binding.onCancel = {
            (currentScreen as? IGMScreen.Shortcuts)?.let {
                replaceTop(it.copy(listening = false, heldKeys = emptySet(), countdownMs = 0))
            }
        }
        heldPastCapture.clear()
        refreshShortcutRows()
        push(IGMScreen.Shortcuts())
    }

    val shortcutRows = androidx.compose.runtime.mutableStateOf<List<RetroArchBridge.ShortcutBinding>>(emptyList())

    private fun refreshShortcutRows() {
        shortcutRows.value = bridge.shortcutBindings()
    }

    private fun stagedShortcut(action: ShortcutAction, change: () -> Unit) {
        providerNav?.markChangedExternally(setOf(ShortcutTable.keyFor(action)))
        change()
        refreshShortcutRows()
    }



    private fun handleShortcutsKey(screen: IGMScreen.Shortcuts, action: MenuAction) {
        val count = ShortcutAction.entries.size
        when (action) {
            MenuAction.UP -> replaceTop(screen.copy(selectedIndex = (screen.selectedIndex - 1 + count) % count))
            MenuAction.DOWN -> replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count))
            MenuAction.CONFIRM -> {
                replaceTop(screen.copy(listening = true, heldKeys = emptySet(), countdownMs = 0))
                binding.startListening()
            }
            MenuAction.NORTH -> shortcutRows.value.getOrNull(screen.selectedIndex)
                ?.takeIf { it.chord.isNotEmpty() }
                ?.let { row -> stagedShortcut(row.action) { bridge.setShortcutBinding(row.action, emptySet()) } }
            MenuAction.BACK -> closeInputScreen()
            else -> {}
        }
    }

    private fun closeInputScreen() {
        pop()
        providerNav?.let { renderProviderState(it.onNav(ProviderSettingsController.Nav.BACK)) }
    }

    val remapRows = androidx.compose.runtime.mutableStateOf<Map<Int, Int>>(emptyMap())
    val remapBase = androidx.compose.runtime.mutableStateOf<Map<Int, Int>>(ButtonRemap.identity())
    val remapNames = androidx.compose.runtime.mutableStateOf<Map<Int, String>>(emptyMap())

    fun openButtonMappings() {
        heldPastCapture.clear()
        refreshRemapRows()
        push(IGMScreen.ButtonMappings())
    }

    private fun refreshRemapRows() {
        remapRows.value = bridge.buttonRemap()
        remapBase.value = bridge.buttonRemapBase()
        remapNames.value = bridge.buttonDescriptors()
    }

    private fun stagedRemap(keys: Set<String>, change: () -> Unit) {
        providerNav?.markChangedExternally(keys)
        change()
        refreshRemapRows()
    }

    private fun bindButtonMapping(screen: IGMScreen.ButtonMappings, keycode: Int) {
        if (keycode in heldPastCapture) return
        if (inputTranslator.isMenuKey(keycode)) {
            replaceTop(screen.copy(listening = false))
            return
        }
        val row = RemapButton.entries.getOrNull(screen.selectedIndex) ?: return
        val canonical = inputTranslator.canonicalFor(keycode)
        val target = canonical?.let(RemapButton::forPosition)
        if (target == null) {
            if (canonical == null && inputTranslator.normalize(keycode) == MenuAction.BACK) {
                replaceTop(screen.copy(listening = false))
            }
            return
        }
        val sends = remapBase.value[target.id]?.takeIf { it != ButtonRemap.UNBOUND } ?: target.id
        if (ButtonRemap.target(remapRows.value, row) != sends) {
            stagedRemap(setOf(ButtonRemap.keyFor(row))) { bridge.setButtonRemap(row, sends) }
        }
        heldPastCapture.add(keycode)
        replaceTop(screen.copy(listening = false))
    }

    private fun handleButtonMappingsKey(screen: IGMScreen.ButtonMappings, action: MenuAction, keycode: Int) {
        val count = RemapButton.entries.size
        val row = RemapButton.entries.getOrNull(screen.selectedIndex)
        when (action) {
            MenuAction.UP -> replaceTop(screen.copy(selectedIndex = (screen.selectedIndex - 1 + count) % count))
            MenuAction.DOWN -> replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count))
            MenuAction.CONFIRM -> {
                heldPastCapture.add(keycode)
                replaceTop(screen.copy(listening = true))
            }
            MenuAction.NORTH -> {
                if (row != null && ButtonRemap.target(remapRows.value, row) != ButtonRemap.UNBOUND) {
                    stagedRemap(setOf(ButtonRemap.keyFor(row))) {
                        bridge.setButtonRemap(row, ButtonRemap.UNBOUND)
                    }
                }
            }
            MenuAction.WEST -> {
                if (!ButtonRemap.isDefault(remapRows.value, remapBase.value)) {
                    stagedRemap(RemapButton.entries.map(ButtonRemap::keyFor).toSet()) {
                        bridge.resetButtonRemap()
                    }
                }
            }
            MenuAction.BACK -> closeInputScreen()
            else -> {}
        }
    }

    fun openCheats() {
        val session = cheatSession ?: return
        push(IGMScreen.Cheats(initialCheatSelection(session)))
    }

    private fun requestCheatsIfMissing() {
        if (cheatFile == null || cheatSession != null) return
        loadCheatFile()
    }

    private fun loadCheatFile() {
        val file = cheatFile ?: return
        cheatSession = null
        renderCheats()
        cheatLoadPending = true
        outstandingCheatLoads++
        bridge.loadCheatFile(file.file.absolutePath)
    }

    private fun onCheatsLoaded(observed: List<RetroArchBridge.CheatRow>) {
        if (outstandingCheatLoads > 0) outstandingCheatLoads--
        if (staleCheatSnapshots > 0) {
            staleCheatSnapshots--
            return
        }
        if (!cheatLoadPending) return
        val manager = cheatManager ?: return
        val file = cheatFile ?: return
        if (observed.isEmpty()) {
            if (outstandingCheatLoads == 0) cheatLoadPending = false
            return
        }
        cheatLoadPending = false
        val open = currentScreen as? IGMScreen.Cheats
        val anchor = open?.let(::cheatAnchorOf)
        val session = CheatSession(manager, file, observed)
        cheatSession = session
        if (pendingCheatRestore.isNotEmpty()) {
            val restored = session.restore(pendingCheatRestore)
            pendingCheatRestore = emptySet()
            for (row in restored) bridge.toggleCheat(row.raIndex)
            if (restored.isNotEmpty()) bridge.applyCheats()
        }
        renderCheats()
        if (open != null) placeCheatSelection(open, anchor)
    }

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

    private fun cheatRestoreRows(): Int = if (cheatHasRemembered.value) 1 else 0

    private fun selectedCheatRow(screen: IGMScreen.Cheats): Int? =
        visibleCheatRows.getOrNull(screen.selectedIndex - cheatRestoreRows())

    private sealed interface CheatAnchor {
        data object Restore : CheatAnchor
        data class Row(val index: Int) : CheatAnchor
    }

    private fun cheatAnchorOf(screen: IGMScreen.Cheats): CheatAnchor? = when {
        cheatRestoreRows() == 1 && screen.selectedIndex == 0 -> CheatAnchor.Restore
        else -> selectedCheatRow(screen)?.let(CheatAnchor::Row)
    }

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
        (currentScreen as? IGMScreen.Cheats)?.let { placeCheatSelection(it, CheatAnchor.Row(rowIndex)) }
    }

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
        if (screen != null) placeCheatSelection(screen, anchor)
        onCheatsRestored?.invoke(restored.size)
    }

    private fun handleCheatsKey(screen: IGMScreen.Cheats, action: MenuAction) {
        val restoreRows = cheatRestoreRows()
        val count = restoreRows + cheatVisibleItems.value.size
        val onRestoreRow = restoreRows == 1 && screen.selectedIndex == 0
        val navigates = action == MenuAction.UP || action == MenuAction.DOWN || action == MenuAction.BACK
        if (cheatLoadPending && !navigates) return
        when (action) {
            MenuAction.UP -> if (count > 0) replaceTop(
                screen.copy(
                    selectedIndex = if (screen.selectedIndex < 0) count - 1
                    else ((screen.selectedIndex - 1) + count) % count
                )
            )
            MenuAction.DOWN -> if (count > 0) replaceTop(
                screen.copy(
                    selectedIndex = if (screen.selectedIndex < 0) 0
                    else (screen.selectedIndex + 1) % count
                )
            )
            MenuAction.CONFIRM -> if (onRestoreRow) {
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
            MenuAction.WEST -> if (cheatItems.value.isNotEmpty()) cycleCheatFilter(screen)
            MenuAction.BACK -> { pop(); if (screenStack.isEmpty()) onClose?.invoke() }
            else -> {}
        }
    }

    private fun needsHardcoreWarning(rowIndex: Int): Boolean {
        if (cheatHardcoreWarned || !bridge.hardcoreActive) return false
        val session = cheatSession ?: return false
        val row = session.rows.getOrNull(rowIndex) ?: return false
        return row.supported && !session.isEnabled(row)
    }

    private fun handleCheatsHardcoreWarningKey(screen: IGMScreen.CheatsHardcoreWarning, action: MenuAction) {
        when (action) {
            MenuAction.CONFIRM -> {
                cheatHardcoreWarned = true
                pop()
                toggleCheatRow(screen.pendingRowIndex)
            }
            MenuAction.BACK -> pop()
            else -> {}
        }
    }

    private var achievementCount = 0

    private fun refreshAchievementCount() {
        if (achievementCount > 0 || !bridge.supportsAchievements) return
        achievementCount = bridge.getAchievements().size
    }

    fun openMenu() {
        menuShortcuts.reset()
        refreshDiskInfo()
        requestCheatsIfMissing()
        refreshAchievementCount()
        players.value = bridge.players()
        screenStack.clear()
        screenStack.add(IGMScreen.Menu(selectedIndex = buildMenuOptions().resumeIndex))
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
        if (slot == SaveSlotStore.AUTO_SLOT) slots.rotateAutoIntoHistory()
        bridge.saveState(slot)
        undoAction.value = if (slotExists.value) UndoAction.SAVE else null
        invalidateSlotCache()
    }

    fun onStateWritten() {
        invalidateSlotCache()
        refreshSlotInfo()
    }

    fun loadState() {
        bridge.loadState(selectedSlotIndex.intValue)
        undoAction.value = UndoAction.LOAD
    }

    fun quitGame() {
        scope.launch {
            if (bridge.savesOnQuit) withContext(io) { slots.rotateAutoIntoHistory() }
            bridge.dropHeldCommands()
            onClose?.invoke()
            bridge.quit()
        }
    }

    fun saveAndQuit() {
        scope.launch {
            withContext(io) { slots.rotateAutoIntoHistory() }
            if (!bridge.savesOnQuit) bridge.forceSaveOnQuit()
            bridge.dropHeldCommands()
            onClose?.invoke()
            bridge.quit()
        }
    }

    fun hasGuides(): Boolean = guideFiles.value.isNotEmpty()

    fun openGuideFromShortcut() {
        val files = guideFiles.value
        if (files.isEmpty()) return
        screenStack.clear()
        if (files.size == 1) openGuide(files[0]) else openGuidePicker()
    }

    fun suspendForNativeMenu() {
        bridge.setOnNativeMenuClosed { onNativeMenuClosed?.invoke() }
        bridge.openNativeMenu()
    }

    fun openAchievements() {
        push(IGMScreen.Achievements(
            achievements = bridge.getAchievements(),
            status = bridge.achievementsStatus(),
        ))
    }

    private fun filteredAchievements(screen: IGMScreen.Achievements): List<AchievementInfo> = when (screen.filter) {
        1 -> screen.achievements.filter { it.unlocked }.sortedByUnlockedNewestFirst()
        2 -> screen.achievements.filter { !it.unlocked }
        3 -> screen.achievements.filter { it.pendingSync }
        else -> screen.achievements
    }

    private fun achievementsHaveMix(list: List<AchievementInfo>): Boolean =
        list.any { it.unlocked } && list.any { !it.unlocked }

    private fun achievementFilterCount(list: List<AchievementInfo>): Int =
        if (list.any { it.pendingSync }) 4 else 3

    private fun handleAchievementsKey(screen: IGMScreen.Achievements, action: MenuAction) {
        val filtered = filteredAchievements(screen)
        val count = filtered.size
        when (action) {
            MenuAction.UP -> if (count > 0) replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count))
            MenuAction.DOWN -> if (count > 0) replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count))
            MenuAction.CONFIRM -> filtered.getOrNull(screen.selectedIndex)?.let {
                push(IGMScreen.AchievementDetail(achievement = it, parentIndex = screen.selectedIndex))
            }
            MenuAction.WEST -> if (achievementsHaveMix(screen.achievements) || screen.achievements.any { it.pendingSync }) {
                replaceTop(screen.copy(
                    filter = (screen.filter + 1) % achievementFilterCount(screen.achievements),
                    selectedIndex = 0,
                ))
            }
            MenuAction.BACK -> { pop(); if (screenStack.isEmpty()) onClose?.invoke() }
            else -> {}
        }
    }

    private fun handleAchievementDetailKey(screen: IGMScreen.AchievementDetail, action: MenuAction) {
        when (action) {
            MenuAction.BACK -> pop()
            else -> {}
        }
    }

    fun attachGuides(manager: GuideManager) = guideController.attach(manager)

    fun onGuideScrollChanged(y: Int, x: Int) = guideController.onScrollChanged(y, x)

    fun openGuidePicker() {
        push(IGMScreen.GuidePicker())
    }

    var openGuideExternally: ((GuideFile, GuideController.GuideOpenState) -> Boolean)? = null

    private fun openGuide(guide: GuideFile) {
        val open = guideController.prepareGuide(guide) ?: return
        if (openGuideExternally?.invoke(guide, open) == true) return
        push(IGMScreen.Guide(filePath = open.filePath, page = open.initialPage, textZoom = open.textZoom))
    }

    private fun handleGuidePickerKey(screen: IGMScreen.GuidePicker, action: MenuAction) {
        val count = guideFiles.value.size
        if (count == 0) { pop(); if (screenStack.isEmpty()) onClose?.invoke(); return }
        when (action) {
            MenuAction.UP -> replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count))
            MenuAction.DOWN -> replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count))
            MenuAction.CONFIRM -> guideFiles.value.getOrNull(screen.selectedIndex)?.let { openGuide(it) }
            MenuAction.BACK -> { pop(); if (screenStack.isEmpty()) onClose?.invoke() }
            else -> {}
        }
    }

    private fun handleReassignPlayersKey(screen: IGMScreen.ReassignPlayers, action: MenuAction) {
        val slots = players.value
        if (slots.isEmpty()) {
            pop()
            return
        }
        val row = screen.selectedIndex
        when (action) {
            MenuAction.UP -> replaceTop(screen.copy(selectedIndex = (row - 1 + slots.size) % slots.size))
            MenuAction.DOWN -> replaceTop(screen.copy(selectedIndex = (row + 1) % slots.size))
            MenuAction.CONFIRM -> {
                val marked = screen.marked
                when {
                    marked == null -> if (slots[row].hasPad) replaceTop(screen.copy(marked = row))
                    marked == row -> replaceTop(screen.copy(marked = null))
                    swapAllowed(slots, marked, row) -> {
                        bridge.swapPlayers(marked, row)
                        players.value = slots.swapped(marked, row)
                        replaceTop(screen.copy(marked = null))
                    }
                }
            }
            MenuAction.BACK -> if (screen.marked != null) replaceTop(screen.copy(marked = null)) else pop()
            else -> {}
        }
    }

    private fun handleGuideKey(screen: IGMScreen.Guide, action: MenuAction) {
        val guide = guideFiles.value.firstOrNull { it.file.absolutePath == screen.filePath } ?: return
        val type = guide.type
        if (screen.help) {
            if (action == MenuAction.MENU || action == MenuAction.BACK) replaceTop(screen.copy(help = false))
            return
        }
        when (action) {
            MenuAction.MENU -> replaceTop(screen.copy(help = true))
            MenuAction.UP -> guideController.scroll(-1)
            MenuAction.DOWN -> guideController.scroll(1)
            MenuAction.LEFT -> if (type != GuideType.TXT && screen.textZoom > 1) guideController.scrollX(-1)
            MenuAction.RIGHT -> if (type != GuideType.TXT && screen.textZoom > 1) guideController.scrollX(1)
            MenuAction.L1 -> if (type == GuideType.PDF) {
                replaceTop(screen.copy(page = (screen.page - 1).coerceAtLeast(0)))
            } else guideController.pageJump(-1)
            MenuAction.R1 -> if (type == GuideType.PDF) {
                replaceTop(screen.copy(page = (screen.page + 1).coerceAtMost(guidePageCount.intValue - 1)))
            } else guideController.pageJump(1)
            MenuAction.NORTH -> {
                guideController.beginZoomReseed()
                replaceTop(screen.copy(textZoom = (screen.textZoom + 1).coerceAtMost(GuideZoom.levels)))
            }
            MenuAction.WEST -> {
                guideController.beginZoomReseed()
                replaceTop(screen.copy(textZoom = (screen.textZoom - 1).coerceAtLeast(1)))
            }
            MenuAction.BACK -> {
                guideController.saveGuide(guide, if (type == GuideType.PDF) screen.page else null, screen.textZoom)
                guideController.scroll(0)
                guideController.scrollX(0)
                pop()
                if (screenStack.isEmpty()) onClose?.invoke()
            }
            else -> {}
        }
    }

    val slotCount get() = slots.slotCount

    var onClose: (() -> Unit)? = null

    var onOpenNativeMenu: (() -> Unit)? = null

    var onNativeMenuClosed: (() -> Unit)? = null

    fun handleKeyUp(keycode: Int) {
        (currentScreen as? IGMScreen.Shortcuts)?.let {
            if (it.listening) { binding.keyUp(keycode); return }
        }
        if (heldPastCapture.remove(keycode)) return
        val shortcut = menuShortcuts.onKeyUp(keycode)
        menuShortcuts.takeReplays().forEach(::replayPress)
        when (shortcut) {
            MenuShortcutGate.Result.CONSUME -> return
            MenuShortcutGate.Result.REPLAY_PRESS -> replayPress(keycode)
            else -> {}
        }
        val screen = currentScreen as? IGMScreen.Guide ?: return
        if (screen.help) return
        when (inputTranslator.normalize(keycode)) {
            MenuAction.UP, MenuAction.DOWN -> guideController.scroll(0)
            MenuAction.LEFT, MenuAction.RIGHT -> guideController.scrollX(0)
            else -> {}
        }
    }

    fun handleKeyDown(keycode: Int, isRepeat: Boolean = false) {
        val screen = currentScreen ?: return
        if (screen is IGMScreen.Shortcuts && screen.listening) {
            binding.keyDown(keycode)
            return
        }
        if (screen is IGMScreen.ButtonMappings && screen.listening) {
            bindButtonMapping(screen, keycode)
            return
        }
        if (keycode in heldPastCapture) return
        val shortcut = menuShortcuts.onKeyDown(keycode, clock(), isRepeat)
        menuShortcuts.takeReplays().forEach(::replayPress)
        when (shortcut) {
            MenuShortcutGate.Result.MENU -> {
                currentScreen?.let { dispatchKey(it, MenuAction.MENU, keycode) }
                return
            }
            MenuShortcutGate.Result.CONSUME -> {
                if (!isRepeat) menuShortcuts.nextDeadline()?.let { deadline ->
                    scheduleMenuHold((deadline - clock()).coerceAtLeast(0L)) { menuShortcutTick(keycode) }
                }
                return
            }
            else -> {}
        }
        val current = currentScreen ?: return
        val action = inputTranslator.normalize(keycode) ?: return
        dispatchKey(current, action, keycode)
    }

    private fun menuShortcutTick(keycode: Int) {
        val shortcut = menuShortcuts.onTick(clock())
        menuShortcuts.takeReplays().forEach(::replayPress)
        if (shortcut == MenuShortcutGate.Result.MENU) {
            currentScreen?.let { dispatchKey(it, MenuAction.MENU, keycode) }
        }
    }

    private fun replayPress(keycode: Int) {
        val screen = currentScreen ?: return
        inputTranslator.normalize(keycode)?.let { dispatchKey(screen, it, keycode) }
    }

    private fun dispatchKey(screen: IGMScreen, action: MenuAction, keycode: Int) {
        when (screen) {
            is IGMScreen.Menu -> handleMenuKey(screen, action)
            is IGMScreen.GuidePicker -> handleGuidePickerKey(screen, action)
            is IGMScreen.Guide -> handleGuideKey(screen, action)
            is IGMScreen.Cheats -> handleCheatsKey(screen, action)
            is IGMScreen.CheatsHardcoreWarning -> handleCheatsHardcoreWarningKey(screen, action)
            is IGMScreen.Achievements -> handleAchievementsKey(screen, action)
            is IGMScreen.AchievementDetail -> handleAchievementDetailKey(screen, action)
            is IGMScreen.PreviewPicker -> handlePreviewPickerKey(screen, action)
            is IGMScreen.ShaderSaveName -> handleShaderSaveNameKey(screen, action)
            is IGMScreen.ProviderSettings -> handleProviderKey(action)
            is IGMScreen.SettingsExitPrompt -> handleProviderKey(action)
            is IGMScreen.Shortcuts -> handleShortcutsKey(screen, action)
            is IGMScreen.ReassignPlayers -> handleReassignPlayersKey(screen, action)
            is IGMScreen.ButtonMappings -> handleButtonMappingsKey(screen, action, keycode)
        }
    }

    private fun handleMenuKey(screen: IGMScreen.Menu, action: MenuAction) {
        val menuOptions = buildMenuOptions()
        val itemCount = menuOptions.actions.size

        if (screen.confirmDeleteSlot) {
            when (action) {
                MenuAction.NORTH -> {
                    slots.delete(selectedSlotIndex.intValue)
                    invalidateSlotCache()
                    refreshSlotInfo()
                    replaceTop(screen.copy(confirmDeleteSlot = false))
                }
                MenuAction.BACK -> replaceTop(screen.copy(confirmDeleteSlot = false))
                else -> {}
            }
            return
        }

        when (action) {
            MenuAction.UP -> {
                val newIndex = if (screen.selectedIndex <= 0) itemCount - 1 else screen.selectedIndex - 1
                replaceTop(screen.copy(selectedIndex = newIndex))
            }
            MenuAction.DOWN -> {
                val newIndex = if (screen.selectedIndex >= itemCount - 1) 0 else screen.selectedIndex + 1
                replaceTop(screen.copy(selectedIndex = newIndex))
            }
            MenuAction.LEFT -> {
                if (menuOptions.actionAt(screen.selectedIndex) == IgmMenuAction.SWITCH_DISC) {
                    cycleDisc(-1)
                    stayOnDiscRow(screen)
                } else {
                    val newSlot = if (selectedSlotIndex.intValue <= 0) slots.slotCount - 1 else selectedSlotIndex.intValue - 1
                    selectedSlotIndex.intValue = newSlot
                    refreshSlotInfo()
                }
            }
            MenuAction.RIGHT -> {
                if (menuOptions.actionAt(screen.selectedIndex) == IgmMenuAction.SWITCH_DISC) {
                    cycleDisc(1)
                    stayOnDiscRow(screen)
                } else {
                    val newSlot = if (selectedSlotIndex.intValue >= slots.slotCount - 1) 0 else selectedSlotIndex.intValue + 1
                    selectedSlotIndex.intValue = newSlot
                    refreshSlotInfo()
                }
            }
            MenuAction.CONFIRM -> {
                selectMenuItem(screen.selectedIndex)
            }
            MenuAction.WEST -> {
                val onSlotRow = screen.selectedIndex == menuOptions.saveStateIndex ||
                    screen.selectedIndex == menuOptions.loadStateIndex
                if (onSlotRow && slotExists.value) replaceTop(screen.copy(confirmDeleteSlot = true))
            }
            MenuAction.NORTH -> {
                when (undoAction.value) {
                    UndoAction.SAVE -> { bridge.undoSaveState(); invalidateSlotCache() }
                    UndoAction.LOAD -> bridge.undoLoadState()
                    null -> return
                }
                undoAction.value = null
                onClose?.invoke()
            }
            MenuAction.BACK, MenuAction.MENU -> {
                onClose?.invoke()
            }
            else -> {}
        }
    }

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

    private fun cycleDisc(direction: Int) {
        if (diskCount <= 1) return
        val next = ((currentDiskIndex.intValue + direction) + diskCount) % diskCount
        if (next == currentDiskIndex.intValue) return
        bridge.setDiskIndex(next)
        currentDiskIndex.intValue = next
        invalidateCheatsForDisc()
    }

    private fun invalidateCheatsForDisc() {
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
            hasReassign = players.value.count { it.hasPad } >= 2,
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
        settingsCanRestore.value = providerNav?.canRestoreDefault() ?: false
        settingsCanReorder.value = providerNav?.canReorderSelection() ?: false
        settingsCanRemovePass.value = providerNav?.canRemoveSelection() ?: false
        settingsCanReset.value = providerNav?.canReset() ?: false
        when (state) {
            is ProviderSettingsController.State.Menu -> {
                if (currentScreen is IGMScreen.PreviewPicker) {
                    settingsItems.value = state.items.map(::toProviderRenderItem)
                    return
                }
                if (state.path == listOf(CuratedCatalog.CATEGORY_SHADER, SHADER_SAVE_SEGMENT)) {
                    if (currentScreen !is IGMScreen.ShaderSaveName) push(IGMScreen.ShaderSaveName())
                    return
                }
                if (state.path == listOf(CuratedCatalog.CATEGORY_INPUT, CuratedCatalog.INPUT_SHORTCUTS)) {
                    if (currentScreen !is IGMScreen.Shortcuts) openShortcuts()
                    return
                }
                if (state.path == listOf(CuratedCatalog.CATEGORY_INPUT, CuratedCatalog.INPUT_BUTTONS)) {
                    if (currentScreen !is IGMScreen.ButtonMappings) openButtonMappings()
                    return
                }
                if (state.path.lastOrNull() == CuratedCatalog.CATEGORY_OVERLAY) {
                    if (currentScreen !is IGMScreen.PreviewPicker) {
                        push(IGMScreen.PreviewPicker(
                            selectedIndex = overlayPicker.refresh(),
                            unwindOnBack = true,
                        ))
                    }
                    return
                }
                val screen = IGMScreen.ProviderSettings(state.selectedIndex, state.path, state.title, state.description, state.descriptionScroll)
                if (currentScreen is IGMScreen.ProviderSettings || currentScreen is IGMScreen.SettingsExitPrompt) {
                    replaceTop(screen)
                } else {
                    push(screen)
                }
                settingsItems.value = state.items.map(::toProviderRenderItem)
            }
            is ProviderSettingsController.State.Prompt -> {
                replaceTop(IGMScreen.SettingsExitPrompt(state.selectedIndex, state.title))
                settingsItems.value = state.options.map { IGMSettingsItem(it) }
            }
            is ProviderSettingsController.State.Closed -> {
                providerNav?.applyPendingChanges()
                providerNav = null
                settingsReordering.value = false
                if (currentScreen is IGMScreen.ProviderSettings || currentScreen is IGMScreen.SettingsExitPrompt) pop()
            }
            is ProviderSettingsController.State.ActionFired -> {}
        }
    }

    private fun toProviderRenderItem(item: GenericIgmSettingsItem): IGMSettingsItem = when (item) {
        is GenericIgmSettingsItem.Category -> IGMSettingsItem(item.label)
        is GenericIgmSettingsItem.Action -> IGMSettingsItem(item.label)
        is GenericIgmSettingsItem.Choice ->
            IGMSettingsItem(item.label, item.value, item.hint, item.description, !item.readOnly)
    }

    private fun handlePreviewPickerKey(screen: IGMScreen.PreviewPicker, action: MenuAction) {
        val picker = overlayPicker
        when (action) {
            MenuAction.LEFT, MenuAction.RIGHT -> {
                val dir = if (action == MenuAction.LEFT) -1 else 1
                val stage = { providerNav?.markChangedExternally(picker.stagedKeys); Unit }
                replaceTop(screen.copy(selectedIndex = picker.cycle(screen.selectedIndex, dir, stage)))
            }
            MenuAction.WEST -> if (picker.canRestore.value) {
                providerNav?.markChangedExternally(picker.stagedKeys)
                picker.onRestoreDefault?.invoke()
                replaceTop(screen.copy(selectedIndex = picker.indexOf(picker.selected.value)))
            }
            MenuAction.BACK -> {
                pop()
                val nav = providerNav
                if (screen.unwindOnBack && nav != null) {
                    renderProviderState(nav.onNav(ProviderSettingsController.Nav.BACK))
                } else if (screenStack.isEmpty()) {
                    onClose?.invoke()
                }
            }
            else -> {}
        }
    }

    private fun handleShaderSaveNameKey(screen: IGMScreen.ShaderSaveName, action: MenuAction) {
        if (screen.help) {
            if (action in setOf(MenuAction.BACK, MenuAction.MENU, MenuAction.START, MenuAction.CONFIRM))
                replaceTop(screen.copy(help = false))
            return
        }
        val kb = screen.keyboard
        fun update(next: dev.cannoli.ui.components.KeyboardState) =
            replaceTop(screen.copy(keyboard = next))
        when (action) {
            MenuAction.UP -> update(KeyboardController.moveSelection(kb, Direction.UP))
            MenuAction.DOWN -> update(KeyboardController.moveSelection(kb, Direction.DOWN))
            MenuAction.LEFT -> update(KeyboardController.moveSelection(kb, Direction.LEFT))
            MenuAction.RIGHT -> update(KeyboardController.moveSelection(kb, Direction.RIGHT))
            MenuAction.CONFIRM -> when (val r = KeyboardController.press(kb)) {
                is KeyboardPress.Update -> update(r.state)
                KeyboardPress.Confirm -> confirmShaderName(kb.text)
            }
            MenuAction.BACK -> update(KeyboardController.backspace(kb))
            MenuAction.WEST -> leaveShaderSaveName()
            MenuAction.NORTH -> update(KeyboardController.insertChar(kb, " "))
            MenuAction.L1 -> update(KeyboardController.moveCursor(kb, -1))
            MenuAction.R1 -> update(KeyboardController.moveCursor(kb, 1))
            MenuAction.L2 -> update(KeyboardController.cursorToStart(kb))
            MenuAction.R2 -> update(KeyboardController.cursorToEnd(kb))
            MenuAction.START -> confirmShaderName(kb.text)
            MenuAction.MENU -> replaceTop(screen.copy(help = true))
            else -> {}
        }
    }

    private fun confirmShaderName(raw: String) {
        val name = raw.filterNot { it in FILENAME_RESERVED }.trim()
        if (name.isEmpty()) return
        (providerNav?.provider as? RaIgmSettingsProvider)?.saveShaderPresetAs(name)
        leaveShaderSaveName()
    }

    private fun leaveShaderSaveName() {
        pop()
        providerNav?.let { renderProviderState(it.onNav(ProviderSettingsController.Nav.BACK)) }
    }

    private fun inShaderTree(): Boolean =
        (currentScreen as? IGMScreen.ProviderSettings)?.path?.firstOrNull() ==
            CuratedCatalog.CATEGORY_SHADER

    private fun handleProviderKey(action: MenuAction) {
        val nav = providerNav ?: return
        if (settingsReordering.value) {
            when (action) {
                MenuAction.UP -> renderProviderState(nav.reorderSelection(-1))
                MenuAction.DOWN -> renderProviderState(nav.reorderSelection(1))
                MenuAction.CONFIRM, MenuAction.START, MenuAction.SELECT -> settingsReordering.value = false
                MenuAction.BACK -> settingsReordering.value = false
                else -> {}
            }
            return
        }
        if (action == MenuAction.SELECT && settingsCanReorder.value) {
            settingsReordering.value = true
            return
        }
        if (action == MenuAction.NORTH && settingsCanRemovePass.value) {
            renderProviderState(nav.removeSelection())
            return
        }
        if (action == MenuAction.NORTH && settingsCanReset.value) {
            renderProviderState(nav.openResetPrompt())
            return
        }
        if (action == MenuAction.WEST && !settingsCanRestore.value) return
        val button = when (action) {
            MenuAction.UP -> ProviderSettingsController.Nav.UP
            MenuAction.DOWN -> ProviderSettingsController.Nav.DOWN
            MenuAction.LEFT -> ProviderSettingsController.Nav.LEFT
            MenuAction.RIGHT -> ProviderSettingsController.Nav.RIGHT
            MenuAction.CONFIRM -> ProviderSettingsController.Nav.CONFIRM
            MenuAction.BACK -> ProviderSettingsController.Nav.BACK
            MenuAction.WEST -> ProviderSettingsController.Nav.WEST
            MenuAction.MENU -> ProviderSettingsController.Nav.HELP
            else -> return
        }
        val wasBuildingChain = inShaderTree()
        renderProviderState(nav.onNav(button))
        if (wasBuildingChain && !inShaderTree()) nav.applyPendingChanges()
    }

    private fun selectMenuItem(index: Int) {
        val opts = menuOptions ?: return
        when (opts.actionAt(index)) {
            IgmMenuAction.RESUME -> onClose?.invoke()
            IgmMenuAction.SAVE_STATE -> { saveState(); onClose?.invoke() }
            IgmMenuAction.LOAD_STATE -> { loadState(); onClose?.invoke() }
            IgmMenuAction.SETTINGS -> openProviderSettings()
            IgmMenuAction.RESET -> { bridge.reset(); onClose?.invoke() }
            IgmMenuAction.QUIT -> quitGame()
            IgmMenuAction.GUIDE -> {
                if (guideFiles.value.size == 1) openGuide(guideFiles.value[0]) else openGuidePicker()
            }
            IgmMenuAction.ACHIEVEMENTS -> openAchievements()
            IgmMenuAction.CHEATS -> openCheats()
            IgmMenuAction.REASSIGN -> push(IGMScreen.ReassignPlayers())
            IgmMenuAction.SWITCH_DISC, null -> {}
        }
    }
}
