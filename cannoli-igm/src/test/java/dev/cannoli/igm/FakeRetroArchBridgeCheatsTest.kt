package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FakeRetroArchBridgeCheatsTest {

    @Test fun `a bridge that ignores cheats still compiles and reports nothing`() {
        val bare = object : RetroArchBridge {
            override fun reset() {}
            override fun quit() {}
            override fun saveState(slot: Int) {}
            override fun loadState(slot: Int) {}
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

    @Test fun `the fake replays the rows it was given when a file is loaded`() {
        val bridge = FakeRetroArchBridge()
        bridge.cheatRowsByPath["/a.cht"] = listOf(
            RetroArchBridge.CheatRow(0, "One", "AAAA", enabled = false, supported = true),
            RetroArchBridge.CheatRow(1, "Two", "BBBB", enabled = false, supported = false),
        )
        var seen: List<RetroArchBridge.CheatRow> = emptyList()
        bridge.setOnCheatsLoaded { seen = it }

        bridge.loadCheatFile("/a.cht")

        assertEquals(listOf("/a.cht"), bridge.loadedCheatPaths)
        assertEquals(2, seen.size)
        assertEquals("Two", seen[1].desc)
    }

    @Test fun `the fake records toggles and applies`() {
        val bridge = FakeRetroArchBridge()
        bridge.toggleCheat(3)
        bridge.applyCheats()
        assertEquals(listOf(3), bridge.toggledCheatIndexes)
        assertEquals(1, bridge.cheatApplies)
    }
}
