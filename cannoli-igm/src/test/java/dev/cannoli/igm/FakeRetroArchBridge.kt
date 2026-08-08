package dev.cannoli.igm

import dev.cannoli.core.SaveSlotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** An IGMController whose slot refresh runs inline; a JVM test has no main dispatcher. */
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

/** A base path nothing was ever saved under, for tests that do not care about slots. */
const val NO_SAVES = "/nonexistent/cannoli-test/Game.state"

open class FakeRetroArchBridge : RetroArchBridge {
    override fun reset() {}
    override fun quit() {}

    var savedSlots = mutableListOf<Int>()
    var loadedSlots = mutableListOf<Int>()
    override fun saveState(slot: Int) { savedSlots += slot }
    override fun loadState(slot: Int) { loadedSlots += slot }

    override var savesOnQuit = false

    var discs = 0
    var disc = 0

    override fun getDiskCount() = discs
    override fun getDiskIndex() = disc
    override fun setDiskIndex(index: Int) { disc = index }

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

    override fun setOnCheatsLoaded(callback: (List<RetroArchBridge.CheatRow>) -> Unit) {
        cheatsLoadedCallback = callback
    }

    // The real bridge queues the load and calls back from the emulator thread. Tests want the
    // deterministic version, so the fake calls back inline.
    override fun loadCheatFile(path: String) {
        loadedCheatPaths += path
        cheatsLoadedCallback?.invoke(cheatRowsByPath[path].orEmpty())
    }

    override fun toggleCheat(index: Int) { toggledCheatIndexes += index }

    override fun applyCheats() { cheatApplies++ }
}
