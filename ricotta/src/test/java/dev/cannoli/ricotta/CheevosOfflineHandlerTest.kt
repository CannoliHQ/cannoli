package dev.cannoli.ricotta

import dev.cannoli.core.achievements.RaOfflineLookup
import dev.cannoli.core.achievements.RaOfflineStore
import dev.cannoli.core.achievements.RaPendingUnlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CheevosOfflineHandlerTest {

    @get:Rule val tmp = TemporaryFolder()

    private data class Env(
        val h: CheevosOfflineHandler,
        val store: RaOfflineStore,
        val pending: RaPendingUnlocks,
    )

    private fun offlineDir() = File(tmp.root, "Offline")

    private fun handler(gameId: Int = 42): Env {
        val offline = offlineDir()
        val pendingDir = File(tmp.root, "Pending")
        val store = RaOfflineStore(offline)
        val pending = RaPendingUnlocks(pendingDir)
        val h = CheevosOfflineHandler(store, RaOfflineLookup(offline), pending, now = { 1000L })
        h.gameId = gameId
        h.platformTag = "SNES"
        return Env(h, store, pending)
    }

    private fun seed(store: RaOfflineStore, gameId: Int = 42, hash: String? = null) {
        store.writeLogin2("""{"Success":true}""")
        store.writeGame(
            gameId = gameId,
            achievementSets = """{"Success":true,"Sets":[]}""",
            startSession = """{"Success":true}""",
            platformTag = "SNES",
            romPath = "/roms/SNES/Game.sfc",
            hash = hash,
        )
    }

    // The shapes rc_client really sends: the first sets request knows only the ROM hash, the session
    // that follows is the first request that carries the game id the server assigned and always ends
    // with the client version in `l`, and an unlock always carries its signature in `v`.
    private val setsRequest = "r=achievementsets&u=p&t=a&m=abcdef0123456789abcdef0123456789"
    private val sessionRequest =
        "r=startsession&u=p&t=a&g=42&h=1&m=abcdef0123456789abcdef0123456789&l=12.4.0"
    private val romHash = "abcdef0123456789abcdef0123456789"
    private val unlockSignature = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
    private val setsBody =
        """{"Success":true,"GameId":42,"Title":"Game","Sets":[{"Title":"Core","Achievements":[{"ID":1,"Points":5}]}]}"""
    // The two answers rc_client reads as an unknown game: a refusal coded not_found, and a success
    // naming a GameId of zero. Everything below them is a failure to answer, not an answer.
    private val unknownGameBody = """{"Success":false,"Error":"Unknown game","Code":"not_found"}"""
    private val serverErrorBody = """{"Success":false,"Error":"Internal error","Code":"api_error"}"""
    private val authFailedBody = """{"Success":false,"Error":"Expired token","Code":"expired_token"}"""
    private val cachedSets = """{"Success":true,"Sets":[]}"""

    private fun unlockRequest(achievementId: Int, hardcore: Boolean = false) =
        "r=awardachievement&u=p&t=a&a=$achievementId&h=${if (hardcore) 1 else 0}" +
            "&m=abc&v=$unlockSignature"

    @Test fun `a set request is not answered while the network has not failed`() {
        val (h, store, _) = handler()
        seed(store)
        assertNull(h.request("r=achievementsets&u=p&t=a&g=42"))
    }

    @Test fun `a set request is answered once the network has failed for it`() {
        val (h, store, _) = handler()
        seed(store)
        h.failed("r=achievementsets&u=p&t=a&g=42")
        assertEquals("""{"Success":true,"Sets":[]}""", h.request("r=achievementsets&u=p&t=a&g=42"))
    }

    @Test fun `an unlock is left to the network while its own attempt has not failed`() {
        val (h, _, pending) = handler()
        assertNull(h.request(unlockRequest(achievementId = 7)))
        assertTrue(pending.list().isEmpty())
    }

    @Test fun `an unlock is taken over once its own attempt has failed`() {
        val (h, _, pending) = handler()
        val request = unlockRequest(achievementId = 7)
        h.failed(request)
        assertEquals("""{"Success":true,"AchievementID":7}""", h.request(request))
        assertEquals(listOf(7), pending.list().map { it.achievementId })
    }

    @Test fun `one unlock failing does not take over the next before it has tried`() {
        val (h, _, pending) = handler()
        h.failed(unlockRequest(achievementId = 7))
        h.request(unlockRequest(achievementId = 7))
        assertNull(h.request(unlockRequest(achievementId = 8)))
        assertEquals(listOf(7), pending.list().map { it.achievementId })
    }

    @Test fun `a spoofed unlock records the request the client built, to be replayed later`() {
        val (h, _, pending) = handler()
        val request = unlockRequest(achievementId = 7, hardcore = true)
        h.failed(request)
        h.request(request)
        assertEquals(request, pending.list().single().body)
    }

    @Test fun `an unlock for a game with no id is not queued, since nothing could submit it`() {
        val (h, _, pending) = handler(gameId = 0)
        val request = "r=awardachievement&u=p&t=a&a=7&h=0&v=$unlockSignature"
        h.failed(request)
        assertNull(h.request(request))
        assertTrue(pending.list().isEmpty())
    }

    @Test fun `a request the cache cannot answer stays unanswered`() {
        val (h, store, _) = handler()
        seed(store)
        h.failed("r=achievementsets&u=p&t=a&g=99")
        assertNull(h.request("r=achievementsets&u=p&t=a&g=99"))
    }

    @Test fun `a game played online is cached under the id the server named, not the one it was asked by`() {
        val (h, store, _) = handler(gameId = 0)
        h.response(setsRequest, setsBody, 200)
        h.response(sessionRequest, """{"Success":true,"HardcoreUnlocks":[]}""", 200)
        assertTrue(store.isCached(42))
        assertEquals("SNES", store.entry(42)?.platformTag)
    }

    @Test fun `the hash the set was asked by is stored, so an offline session can find it again`() {
        val (h, _, _) = handler(gameId = 0)
        h.response(setsRequest, setsBody, 200)
        h.response(sessionRequest, """{"Success":true}""", 200)
        assertEquals("abcdef0123456789abcdef0123456789", File(offlineDir(), "42/hashes").readText())
        assertEquals(setsBody, RaOfflineLookup(offlineDir()).bodyFor(setsRequest))
    }

    @Test fun `a login response is recorded so a cold start offline can log in`() {
        val (h, _, _) = handler()
        h.response("r=login2&u=p&t=a", """{"Success":true,"User":"p"}""", 200)
        assertEquals("""{"Success":true,"User":"p"}""", RaOfflineLookup(offlineDir()).bodyFor("r=login2&u=p&t=a"))
    }

    @Test fun `a failed response is not recorded`() {
        val (h, store, _) = handler()
        h.response(setsRequest, "", 500)
        h.response(sessionRequest, "", 500)
        assertFalse(store.isCached(42))
    }

    @Test fun `a response the server refused is not recorded even with a 200`() {
        val (h, store, _) = handler()
        h.response(setsRequest, """{"Success":false,"Error":"bad token"}""", 200)
        h.response(sessionRequest, """{"Success":true}""", 200)
        assertFalse(store.isCached(42))
    }

    @Test fun `a body with no Success field, or one that is not json at all, is not recorded`() {
        val (h, store, _) = handler()
        h.response(setsRequest, """{"Error":"whatever"}""", 200)
        h.response(setsRequest, "<html>", 200)
        h.response(sessionRequest, """{"Success":true}""", 200)
        assertFalse(store.isCached(42))
    }

    @Test fun `a Success field with spaces around the colon is still accepted`() {
        val (h, store, _) = handler()
        h.response(setsRequest, """{"Success" : true,"GameId":42,"Sets":[]}""", 200)
        h.response(sessionRequest, """{"Success" : true}""", 200)
        assertTrue(store.isCached(42))
    }

    @Test fun `a set the server does not recognise is answered from the cache the override filled`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = romHash)
        assertEquals(cachedSets, h.response(setsRequest, unknownGameBody, 404))
    }

    // Without this the cache of an overridden game could only ever be refreshed by preloading it
    // again: the server refuses to identify the ROM, so write-through never gets a set to pair with
    // the session, and the unlock state stayed frozen at whatever the last preload saw.
    @Test fun `an overridden game refreshes its cached session by being played online`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = romHash)
        h.response(setsRequest, unknownGameBody, 404)

        h.response(sessionRequest, """{"Success":true,"Unlocks":[{"ID":7}]}""", 200)

        assertEquals(
            """{"Success":true,"Unlocks":[{"ID":7}]}""",
            File(offlineDir(), "42/startsession.json").readText(),
        )
    }

    // The game process is handed content by RetroArch and has no launcher-side path to write, so a
    // write-through used to blank the path a preload had recorded, which is what the offline
    // browser re-preloads from.
    @Test fun `a write-through keeps the rom path the preload recorded`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = romHash)
        h.response(setsRequest, unknownGameBody, 404)

        h.response(sessionRequest, """{"Success":true}""", 200)

        assertEquals("SNES\n/roms/SNES/Game.sfc", File(offlineDir(), "42/source").readText())
    }

    @Test fun `a substituted set does not make an online session read as offline`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = romHash)
        h.response(setsRequest, unknownGameBody, 404)
        assertFalse(h.servedFromCache())
    }

    @Test fun `a set answered with a game id of zero counts as unrecognised too`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = romHash)
        val zeroed = """{"Success":true,"GameId":0,"Title":"","Sets":[]}"""
        assertEquals(cachedSets, h.response(setsRequest, zeroed, 200))
    }

    // A server that failed to answer is not a server saying the game does not exist. Substituting
    // here would bury a real error under stale definitions the player cannot tell from live ones,
    // and every one of these three is a case rc_client itself fails the load on.
    @Test fun `a set request the server failed on is not answered from the cache`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = romHash)
        assertNull(h.response(setsRequest, serverErrorBody, 500))
    }

    @Test fun `a set request answered with an empty body is not answered from the cache`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = romHash)
        assertNull(h.response(setsRequest, "", 500))
    }

    @Test fun `a set request refused for a bad token is not answered from the cache`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = romHash)
        assertNull(h.response(setsRequest, authFailedBody, 403))
    }

    @Test fun `a set the server does not recognise for a hash nothing cached stays unanswered`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = "00000000000000000000000000000000")
        assertNull(h.response(setsRequest, unknownGameBody, 404))
    }

    @Test fun `a set response that names a game is passed through and recorded, not replaced`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = romHash)
        assertNull(h.response(setsRequest, setsBody, 200))
        h.response(sessionRequest, """{"Success":true}""", 200)
        assertEquals(setsBody, RaOfflineLookup(offlineDir()).bodyFor(setsRequest))
    }

    @Test fun `a session response is never replaced, whatever the server said`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = romHash)
        assertNull(h.response(sessionRequest, unknownGameBody, 404))
    }

    @Test fun `a login response is never replaced`() {
        val (h, store, _) = handler(gameId = 0)
        seed(store, hash = romHash)
        assertNull(h.response("r=login2&u=p&t=a", unknownGameBody, 404))
    }

    // The bridge builds the handler behind nativeInit, so the gate itself is what a unit test can
    // reach. Everything below it can put words in the server's mouth, which is what makes a
    // hardcore session the one this must stay out of entirely.
    @Test fun `a softcore session gets the offline handler`() {
        assertTrue(EmbeddedRetroArchBridge.cheevosOfflineAllowedFor(hardcoreInEffect = false))
    }

    @Test fun `a hardcore session gets no offline handler at all`() {
        assertFalse(EmbeddedRetroArchBridge.cheevosOfflineAllowedFor(hardcoreInEffect = true))
    }

    @Test fun `serving from cache is remembered, so the menu can say so`() {
        val (h, store, _) = handler()
        seed(store)
        assertFalse(h.servedFromCache())
        h.failed("r=startsession&u=p&t=a&g=42&l=12.4.0")
        h.request("r=startsession&u=p&t=a&g=42&l=12.4.0")
        assertTrue(h.servedFromCache())
    }
}
