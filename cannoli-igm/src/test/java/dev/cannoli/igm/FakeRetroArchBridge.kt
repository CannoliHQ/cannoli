package dev.cannoli.igm

import dev.cannoli.core.SaveSlotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

fun testController(
    bridge: RetroArchBridge,
    gameTitle: String = "Game",
    slots: SaveSlotStore = SaveSlotStore(NO_SAVES),
): IGMController =
    IGMController(
        bridge,
        gameTitle,
        slots,
        CoroutineScope(Dispatchers.Unconfined),
        Dispatchers.Unconfined,
    )

const val NO_SAVES = "/nonexistent/cannoli-test/Game.state"

open class FakeRetroArchBridge : RetroArchBridge {
    val callOrder = mutableListOf<String>()

    override fun reset() {}
    override fun quit() { callOrder += "quit" }
    override fun dropHeldCommands() { callOrder += "dropHeldCommands" }

    var savedSlots = mutableListOf<Int>()
    var loadedSlots = mutableListOf<Int>()
    override fun saveState(slot: Int) { savedSlots += slot }
    override fun loadState(slot: Int) { loadedSlots += slot }

    var undoneSaves = 0
    var undoneLoads = 0
    override fun undoSaveState() { undoneSaves++ }
    override fun undoLoadState() { undoneLoads++ }

    override var savesOnQuit = false

    var discs = 0
    var disc = 0

    override fun getDiskCount() = discs
    override fun getDiskIndex() = disc
    override fun setDiskIndex(index: Int) { disc = index }

    var playerSlots: List<PlayerSlot> = emptyList()
    val swaps = mutableListOf<Pair<Int, Int>>()

    override fun players() = playerSlots
    override fun swapPlayers(a: Int, b: Int) { swaps += a to b }

    val remap = mutableMapOf<Int, Int>()
    val remapSets = mutableListOf<Pair<Int, Int>>()

    override fun buttonRemap(): Map<Int, Int> = remap.toMap()
    override fun setButtonRemap(button: RemapButton, target: Int) {
        remap[button.id] = target
        remapSets += button.id to target
    }

    var remapBase: Map<Int, Int> = ButtonRemap.identity()
    var remapResets = 0

    override fun buttonRemapBase(): Map<Int, Int> = remapBase
    override fun resetButtonRemap() {
        remap.clear()
        remapResets++
    }

    var descriptors: Map<Int, String> = emptyMap()
    override fun buttonDescriptors(): Map<Int, String> = descriptors

    var nativeMenuOpened = 0
    private var menuClosedCallback: (() -> Unit)? = null

    fun closeNativeMenu() = menuClosedCallback?.invoke()

    override fun openNativeMenu() { nativeMenuOpened++ }
    override fun setOnNativeMenuClosed(callback: () -> Unit) { menuClosedCallback = callback }
    override val supportsAchievements = false

    val cheatRowsByPath = mutableMapOf<String, List<RetroArchBridge.CheatRow>>()
    val loadedCheatPaths = mutableListOf<String>()
    val toggledCheatIndexes = mutableListOf<Int>()
    var cheatApplies = 0
    override var hardcoreActive = false

    private var cheatsLoadedCallback: ((List<RetroArchBridge.CheatRow>) -> Unit)? = null

    var deferCheatLoads = false
    private val undeliveredCheatLoads = mutableListOf<String>()

    override fun setOnCheatsLoaded(callback: (List<RetroArchBridge.CheatRow>) -> Unit) {
        cheatsLoadedCallback = callback
    }

    override fun loadCheatFile(path: String) {
        loadedCheatPaths += path
        if (deferCheatLoads) undeliveredCheatLoads += path
        else cheatsLoadedCallback?.invoke(cheatRowsByPath[path].orEmpty())
    }

    fun deliverCheats() {
        val path = undeliveredCheatLoads.removeFirstOrNull() ?: return
        cheatsLoadedCallback?.invoke(cheatRowsByPath[path].orEmpty())
    }

    fun dropPendingCheatLoads() = undeliveredCheatLoads.clear()

    override fun toggleCheat(index: Int) { toggledCheatIndexes += index }

    override fun applyCheats() { cheatApplies++ }
}
