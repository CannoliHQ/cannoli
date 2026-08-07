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
}
