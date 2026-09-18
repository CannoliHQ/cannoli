package dev.cannoli.core.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RaOfflineLookupTest {

    @get:Rule val tmp = TemporaryFolder()
    private var storeCount = 0

    private fun store(): File = tmp.newFolder("Offline${storeCount++}")

    private fun seed(dir: File, gameId: Int, hash: String? = null) {
        val store = RaOfflineStore(dir)
        store.writeLogin2("""{"Success":true,"User":"player"}""")
        store.writeGame(
            gameId = gameId,
            achievementSets = """{"Success":true,"Sets":[{"GameId":$gameId}]}""",
            startSession = """{"Success":true,"HardcoreUnlocks":[]}""",
            platformTag = "SNES",
            romPath = "/roms/SNES/Game.sfc",
            hash = hash,
        )
    }

    @Test fun `a login request is answered from the root file`() {
        val dir = store()
        seed(dir, 42)
        assertEquals(
            """{"Success":true,"User":"player"}""",
            RaOfflineLookup(dir).bodyFor("r=login2&u=player&t=abc"),
        )
    }

    @Test fun `an achievement set request is answered by game id`() {
        val dir = store()
        seed(dir, 42)
        val body = RaOfflineLookup(dir).bodyFor("r=achievementsets&u=player&t=abc&g=42")
        assertEquals("""{"Success":true,"Sets":[{"GameId":42}]}""", body)
    }

    @Test fun `a session request is answered by game id`() {
        val dir = store()
        seed(dir, 42)
        val body = RaOfflineLookup(dir).bodyFor("r=startsession&u=player&t=abc&g=42&l=12.4.0")
        assertEquals("""{"Success":true,"HardcoreUnlocks":[]}""", body)
    }

    @Test fun `a session request with no game id falls back to the rom hash`() {
        val dir = store()
        seed(dir, 42, hash = "a1b2c3d4e5f6")
        val body = RaOfflineLookup(dir).bodyFor("r=startsession&u=player&t=abc&m=a1b2c3d4e5f6")
        assertEquals("""{"Success":true,"HardcoreUnlocks":[]}""", body)
    }

    // A manual Game ID points several ROMs at one game, and each of them asks by its own hash.
    // Keying the directory on the newest alone let the second preload evict the first, which went
    // silent: the game that lost simply stopped being recognised offline.
    @Test fun `every rom cached under one game id keeps answering`() {
        val dir = store()
        seed(dir, 42, hash = "a1b2c3d4e5f6")
        seed(dir, 42, hash = "f6e5d4c3b2a1")
        val lookup = RaOfflineLookup(dir)
        assertEquals(42, lookup.gameIdFor("r=startsession&u=player&t=abc&m=a1b2c3d4e5f6"))
        assertEquals(42, lookup.gameIdFor("r=startsession&u=player&t=abc&m=f6e5d4c3b2a1"))
    }

    // What an install cached before a game could answer for several ROMs still serves, so an
    // existing cache does not go quiet until its next preload rewrites it.
    @Test fun `a cache written with a single hash file still answers`() {
        val dir = store()
        seed(dir, 42)
        File(dir, "42/hashes").delete()
        File(dir, "42/hash").writeText("a1b2c3d4e5f6")
        assertEquals(42, RaOfflineLookup(dir).gameIdFor("r=startsession&u=player&t=abc&m=a1b2c3d4e5f6"))
    }

    @Test fun `a hash nothing was cached under is not answered`() {
        val dir = store()
        seed(dir, 42, hash = "a1b2c3d4e5f6")
        assertNull(RaOfflineLookup(dir).bodyFor("r=startsession&u=player&t=abc&m=ffffffffffff"))
    }

    @Test fun `a game that was never cached is not answered`() {
        val dir = store()
        seed(dir, 42)
        assertNull(RaOfflineLookup(dir).bodyFor("r=achievementsets&u=player&t=abc&g=99"))
    }

    @Test fun `a request type the cache does not hold is not answered`() {
        val dir = store()
        seed(dir, 42)
        assertNull(RaOfflineLookup(dir).bodyFor("r=ping&u=player&t=abc&g=42"))
        assertNull(RaOfflineLookup(dir).bodyFor("r=awardachievement&u=player&t=abc&a=7&h=0"))
    }

    @Test fun `a game id that is not a number cannot reach outside the cache`() {
        val dir = store()
        seed(dir, 42)
        assertNull(RaOfflineLookup(dir).bodyFor("r=achievementsets&u=player&t=abc&g=../../etc/passwd"))
        assertNull(RaOfflineLookup(dir).bodyFor("r=achievementsets&u=player&t=abc&g=-1"))
        assertNull(RaOfflineLookup(dir).bodyFor("r=achievementsets&u=player&t=abc&g="))
    }

    @Test fun `a hash that is not hex cannot reach outside the cache`() {
        val dir = store()
        seed(dir, 42, hash = "a1b2c3d4e5f6")
        assertNull(RaOfflineLookup(dir).bodyFor("r=startsession&u=player&t=abc&m=../../hash"))
    }

    @Test fun `a field is read by name and url decoded`() {
        assertEquals("a b", RaOfflineLookup.field("r=login2&u=a+b&t=x", "u"))
        assertEquals("a b", RaOfflineLookup.field("r=login2&u=a%20b&t=x", "u"))
        assertNull(RaOfflineLookup.field("r=login2&u=player", "g"))
    }

    @Test fun `the request type is the r field`() {
        assertEquals("startsession", RaOfflineLookup(store()).requestType("r=startsession&g=1"))
        assertNull(RaOfflineLookup(store()).requestType("u=player"))
    }

    @Test fun `an empty body answers nothing`() {
        assertNull(RaOfflineLookup(store()).bodyFor(""))
    }

    @Test fun `a cached file that is only whitespace answers null, not an empty body`() {
        val dir = store()
        seed(dir, 42)
        File(dir, "login2.json").writeText("   \n")
        assertNull(RaOfflineLookup(dir).bodyFor("r=login2&u=player&t=abc"))
    }

    @Test fun `the game a request is about is named, by id or by hash`() {
        val dir = store()
        seed(dir, 42, hash = "a1b2c3d4e5f6")
        val lookup = RaOfflineLookup(dir)
        assertEquals(42, lookup.gameIdFor("r=startsession&u=p&t=a&g=42"))
        assertEquals(42, lookup.gameIdFor("r=startsession&u=p&t=a&m=a1b2c3d4e5f6"))
        assertNull(lookup.gameIdFor("r=awardachievement&u=p&t=a&a=7&h=0"))
    }
}
