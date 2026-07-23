package dev.cannoli.scorza.ra

import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.Rom
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RaPreloadEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val setsBody = """
        {"Success":true,"GameId":55,"Title":"Super Metroid",
         "Sets":[{"Title":"Core","Achievements":[{"ID":1,"Points":5},{"ID":2,"Points":10}]}]}
    """.trimIndent()

    private fun client(server: MockWebServer): RaConnectClient {
        val ok = OkHttpClient()
        return RaConnectClient(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            clientProvider = { ok }, userAgent = "x",
        )
    }

    private fun engine(repo: RomsRepository) = RaPreloadEngine(
        store = RaOfflineStore(tmp.root),
        romsRepository = repo,
        username = "bob",
        token = "tok",
        hasher = { _, _ -> "deadbeef" },
    )

    private fun rom(id: Long = 1L, raGameId: Int? = null, tag: String = "SNES") = Rom(
        id = id,
        path = File("/roms/sm.sfc"),
        platformTag = tag,
        displayName = "Super Metroid",
        raGameId = raGameId,
    )

    @Test fun preloadOne_success_marksRomCached() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true,"User":"bob","Token":"tok"}"""))
        server.enqueue(MockResponse().setBody(setsBody))
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        server.start()
        val repo = mockk<RomsRepository>(relaxed = true)

        val result = engine(repo).preloadOne(client(server), rom(id = 42L, raGameId = 55))

        assertTrue(result is RaOfflinePreloader.Result.Success)
        verify(exactly = 1) { repo.setRaCachedGameId(42L, 55) }
        server.shutdown()
    }

    @Test fun preloadOne_noAchievements_doesNotMark() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        server.enqueue(MockResponse().setBody("""{"Success":true,"Title":"X","Sets":[]}"""))
        server.start()
        val repo = mockk<RomsRepository>(relaxed = true)

        val result = engine(repo).preloadOne(client(server), rom(raGameId = 55))

        assertTrue(result is RaOfflinePreloader.Result.NoAchievements)
        verify(exactly = 0) { repo.setRaCachedGameId(any(), any()) }
        server.shutdown()
    }

    @Test fun preloadOne_resolveOffline_failsWithoutMarking() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        val repo = mockk<RomsRepository>(relaxed = true)

        val result = engine(repo).preloadOne(client(server), rom(raGameId = null))

        assertTrue(result is RaOfflinePreloader.Result.Failure)
        verify(exactly = 0) { repo.setRaCachedGameId(any(), any()) }
        server.shutdown()
    }

    @Test fun preloadAll_countsSuccessesAndSurvivesFailures() = runBlocking {
        val server = MockWebServer()
        // rom 1 (game 55): full success
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        server.enqueue(MockResponse().setBody(setsBody))
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        // rom 2 (game 77): login ok, achievementsets errors -> failure, batch continues
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        val repo = mockk<RomsRepository>(relaxed = true)

        val cached = engine(repo).preloadAll(
            client(server),
            listOf(rom(id = 1L, raGameId = 55), rom(id = 2L, raGameId = 77)),
        )

        assertEquals(1, cached)
        verify(exactly = 1) { repo.setRaCachedGameId(1L, 55) }
        verify(exactly = 0) { repo.setRaCachedGameId(2L, 77) }
        server.shutdown()
    }
}
