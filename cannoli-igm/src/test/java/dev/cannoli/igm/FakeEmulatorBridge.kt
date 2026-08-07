package dev.cannoli.igm

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** An IGMController whose slot refresh runs inline; a JVM test has no main dispatcher. */
fun testController(bridge: EmulatorBridge, gameTitle: String = "Game"): IGMController =
    IGMController(bridge, gameTitle, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

open class FakeEmulatorBridge : EmulatorBridge {
    override fun reset() {}
    override fun quit() {}
    override fun pause() {}
    override fun unpause() {}
    override fun isPaused() = false
    override fun saveState(slot: Int) {}
    override fun loadState(slot: Int) {}
    override fun undoSaveState() {}
    override fun undoLoadState() {}
    override fun getStateSlotCount() = 11
    override fun getStateThumbnail(slot: Int): Bitmap? = null
    override fun stateExists(slot: Int) = false
    override fun getDiskCount() = 0
    override fun getDiskIndex() = 0
    override fun setDiskIndex(index: Int) {}
    override fun getDiskLabel(index: Int): String? = null
    var nativeMenuOpened = 0
    private var menuClosedCallback: (() -> Unit)? = null

    fun closeNativeMenu() = menuClosedCallback?.invoke()

    override fun openNativeMenu() { nativeMenuOpened++ }
    override fun openAchievementsMenu() {}
    override fun setOnNativeMenuClosed(callback: () -> Unit) { menuClosedCallback = callback }
    override val supportsNativeMenu = true
    override val supportsAchievements = false
    override val supportsUndo = true
}
