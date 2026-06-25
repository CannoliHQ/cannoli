package dev.cannoli.scorza.ra

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RaOfflinePreloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun preloader(server: MockWebServer): Pair<RaOfflinePreloader, RaOfflineStore> {
        val ok = OkHttpClient()
        val client = RaConnectClient(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            clientProvider = { ok }, userAgent = "x",
        )
        val store = RaOfflineStore(tmp.root)
        return RaOfflinePreloader(client, store) to store
    }

    private val setsBody = """
        {"Success":true,"GameId":55,"Title":"Super Metroid",
         "Sets":[{"Title":"Core","Achievements":[
           {"ID":1,"Points":5},{"ID":2,"Points":10}]}]}
    """.trimIndent()

    @Test fun success_storesBodiesAndDerivableEntry() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true,"User":"bob","Token":"tok"}"""))
        server.enqueue(MockResponse().setBody(setsBody))
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        server.start()
        val (pre, store) = preloader(server)

        val result = pre.preload("/roms/sm.sfc", "SNES", 55, "bob", "tok")

        assertTrue(result is RaOfflinePreloader.Result.Success)
        result as RaOfflinePreloader.Result.Success
        assertEquals("Super Metroid", result.gameName)
        assertEquals(2, result.achievementCount)
        assertEquals(15, result.totalPoints)

        val entries = store.entries()
        assertEquals(1, entries.size)
        assertEquals("Super Metroid", entries[0].gameName)
        assertEquals("/roms/sm.sfc", entries[0].romPath)
        assertTrue(tmp.root.resolve("login2.json").exists())
        assertTrue(tmp.root.resolve("55/achievementsets.json").exists())
        assertTrue(tmp.root.resolve("55/startsession.json").exists())
        server.shutdown()
    }

    @Test fun success_withHash_writesHashFile() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true,"User":"bob","Token":"tok"}"""))
        server.enqueue(MockResponse().setBody(setsBody))
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        server.start()
        val (pre, _) = preloader(server)
        pre.preload("/roms/sm.sfc", "SNES", 55, "bob", "tok", hash = "abc123")
        assertEquals("abc123", tmp.root.resolve("55/hash").readText())
        server.shutdown()
    }

    @Test fun noAchievements_returnsNoAchievements_noGameDir() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true,"User":"bob","Token":"tok"}"""))
        server.enqueue(MockResponse().setBody("""{"Success":true,"Title":"X","Sets":[]}"""))
        server.start()
        val (pre, store) = preloader(server)
        val result = pre.preload("/roms/x.sfc", "SNES", 7, "bob", "tok")
        assertTrue(result is RaOfflinePreloader.Result.NoAchievements)
        assertTrue(store.entries().isEmpty())
        server.shutdown()
    }

    @Test fun loginFailure_returnsFailure() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"Success":false}"""))
        server.start()
        val (pre, _) = preloader(server)
        val result = pre.preload("/roms/x.sfc", "SNES", 7, "bob", "bad")
        assertTrue(result is RaOfflinePreloader.Result.Failure)
        server.shutdown()
    }
}
