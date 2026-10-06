package dev.cannoli.igm

import org.junit.Assert.assertFalse
import org.junit.Test

class FakeRetroArchBridgeCheatsTest {

    @Test fun `a bridge that ignores cheats still compiles and reports nothing`() {
        val bare = object : RetroArchBridge {
            override fun reset() {}
            override fun quit() {}
            override fun saveState(slot: Int) {}
            override fun loadState(slot: Int) {}
            override fun undoSaveState() {}
            override fun undoLoadState() {}
            override val savesOnQuit = false
            override val supportsAchievements = false
            override fun getDiskCount() = 0
            override fun getDiskIndex() = 0
            override fun setDiskIndex(index: Int) {}
            override fun openNativeMenu() {}
            override fun setOnNativeMenuClosed(callback: () -> Unit) {}
        }
        assertFalse(bare.hardcoreActive)
        bare.loadCheatFile("/nope.cht")
        bare.toggleCheat(0)
        bare.applyCheats()
    }
}
