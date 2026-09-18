package dev.cannoli.core.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RaOfflineStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sets(title: String, vararg points: Int): String {
        val achs = points.joinToString(",") { """{"Points":$it}""" }
        return """{"Success":true,"Title":"$title","Sets":[{"Achievements":[$achs]}]}"""
    }

    @Test
    fun writeGame_entriesDeriveMetadataFromDisk() {
        val store = RaOfflineStore(tmp.root)
        store.writeLogin2("login")
        store.writeGame(55, sets("Super Metroid", 5, 10), "session", "SNES", "/roms/sm.sfc", "abc")

        assertTrue(tmp.root.resolve("55/achievementsets.json").exists())
        assertTrue(tmp.root.resolve("55/startsession.json").exists())
        assertEquals("SNES\n/roms/sm.sfc", tmp.root.resolve("55/source").readText())
        assertEquals("abc", tmp.root.resolve("55/hashes").readText())

        val entries = store.entries()
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals(55, e.gameId)
        assertEquals("Super Metroid", e.gameName)
        assertEquals(2, e.achievementCount)
        assertEquals(15, e.totalPoints)
        assertEquals("SNES", e.platformTag)
        assertEquals("/roms/sm.sfc", e.romPath)
        assertTrue(e.cachedAtMs > 0)
    }

    @Test
    fun writeGame_keepsEveryRomCachedUnderOneGameId() {
        val store = RaOfflineStore(tmp.root)
        store.writeGame(55, sets("Super Metroid", 5, 10), "session", "SNES", "/roms/one.sfc", "abc")
        store.writeGame(55, sets("Super Metroid", 5, 10), "session", "SNES", "/roms/two.sfc", "def")
        store.writeGame(55, sets("Super Metroid", 5, 10), "session", "SNES", "/roms/two.sfc", "DEF")

        assertEquals(listOf("abc", "def"), tmp.root.resolve("55/hashes").readText().lines())
        assertEquals(1, store.entries().size)
    }

    @Test
    fun entries_sortedByGameName_skipsCorruptDir() {
        val store = RaOfflineStore(tmp.root)
        store.writeGame(1, sets("Zelda", 5), "s", "NES", "/z.nes", null)
        store.writeGame(2, sets("Castlevania", 5), "s", "NES", "/c.nes", null)
        File(tmp.root, "3").apply { mkdirs() } // no achievementsets.json -> skipped
        assertEquals(listOf("Castlevania", "Zelda"), store.entries().map { it.gameName })
    }

    @Test
    fun entries_sortedByPlatformThenName() {
        val store = RaOfflineStore(tmp.root)
        store.writeGame(1, sets("Zelda", 5), "s", "NES", "/z.nes", null)
        store.writeGame(2, sets("Sonic", 5), "s", "MD", "/s.md", null)
        store.writeGame(3, sets("Castlevania", 5), "s", "NES", "/c.nes", null)
        // MD before NES (platform), then name within platform
        assertEquals(listOf("Sonic", "Castlevania", "Zelda"), store.entries().map { it.gameName })
    }

    @Test
    fun isCached_byGameId() {
        val store = RaOfflineStore(tmp.root)
        store.writeGame(7, sets("Metroid", 5), "s", "NES", "/roms/metroid.nes", null)
        assertTrue(store.isCached(7))
        assertFalse(store.isCached(8))
    }

    @Test
    fun deleteGame_removesDir_keepsOthers_dropsLogin2WhenLast() {
        val store = RaOfflineStore(tmp.root)
        store.writeLogin2("login")
        store.writeGame(1, sets("A", 5), "s", "NES", "/a.nes", null)
        store.writeGame(2, sets("B", 5), "s", "NES", "/b.nes", null)

        store.deleteGame(1)
        assertFalse(tmp.root.resolve("1").exists())
        assertEquals(listOf(2), store.entries().map { it.gameId })
        assertTrue(tmp.root.resolve("login2.json").exists())

        store.deleteGame(2)
        assertTrue(store.entries().isEmpty())
        assertFalse(tmp.root.resolve("login2.json").exists())
    }

    @Test
    fun entries_dropsEntryWithMissingSource() {
        val store = RaOfflineStore(tmp.root)
        store.writeGame(7, sets("Metroid", 5), "s", "NES", "/m.nes", null)
        File(tmp.root, "7/source").delete()
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun entries_dropsEntryWithBlankPlatform() {
        val store = RaOfflineStore(tmp.root)
        store.writeGame(8, sets("Zelda", 5), "s", "NES", "/z.nes", null)
        File(tmp.root, "8/source").writeText("\n/z.nes")
        assertTrue(store.entries().isEmpty())
    }

    @Test fun `two writers scratch in files of their own, so neither can publish the other's half`() {
        val dir = tmp.newFolder("Offline")
        val a = RaOfflineStore(dir, writerTag = "a")
        val b = RaOfflineStore(dir, writerTag = "b")
        File(dir, "login2.json.b.tmp").mkdirs()
        assertTrue(a.writeLogin2("good"))
        assertFalse(b.writeLogin2("new"))
        assertEquals("good", File(dir, "login2.json").readText())
    }

    @Test fun `a login write that cannot use its temporary file leaves the stored one alone`() {
        val dir = tmp.newFolder("Offline")
        val store = RaOfflineStore(dir, writerTag = "w")
        store.writeLogin2("good")
        // A directory where the temporary file must go, so the write cannot get that far. A writer
        // that wrote straight to the target would sail past this and replace a good body.
        File(dir, "login2.json.w.tmp").mkdirs()
        assertFalse(store.writeLogin2("new"))
        assertEquals("good", File(dir, "login2.json").readText())
    }

    @Test fun `a game write that cannot use its temporary file fails rather than half writing`() {
        val dir = tmp.newFolder("Offline")
        val store = RaOfflineStore(dir, writerTag = "w")
        File(dir, "7").mkdirs()
        File(dir, "7/achievementsets.json.w.tmp").mkdirs()
        assertFalse(store.writeGame(7, sets("good-sets", 5), "session", "SNES", "/roms/SNES/G.sfc", null))
        assertTrue(store.entries().isEmpty())
    }
}
