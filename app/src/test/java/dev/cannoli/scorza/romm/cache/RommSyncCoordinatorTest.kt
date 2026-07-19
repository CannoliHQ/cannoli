package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.PlatformMap
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.RommSlugMap
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommSyncCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var db: RommDatabase

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        db = RommDatabase { File(tmp.newFolder("Config"), "romm.db") }
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    // The slug map maps snes -> SNES; isSupported accepts every tag.
    private fun coordinator(): RommSyncCoordinator {
        val client = RommClient({ server.url("/").toString().trimEnd('/') }, { OkHttpClient() })
        val platformMap = PlatformMap(RommSlugMap.parse("""{"snes":"SNES"}""")) { true }
        return RommSyncCoordinator(client, platformMap, db)
    }

    private fun json(body: String) = MockResponse().setBody(body)

    @Test fun `full sync pulls platforms and their roms`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/api/platforms") -> json("""[{"id":1,"slug":"snes","rom_count":2,"display_name":"SNES","updated_at":"2024-01-01T00:00:00"}]""")
                    path.startsWith("/api/roms") -> json("""{"items":[
                        {"id":10,"platform_id":1,"fs_name":"a.sfc","name":"Alpha","updated_at":"2024-02-02T00:00:00"},
                        {"id":11,"platform_id":1,"fs_name":"b.sfc","name":"Beta","updated_at":"2024-03-03T00:00:00"}
                    ],"total":2,"limit":100,"offset":0}""")
                    else -> json("{}")
                }
            }
        }

        val coord = coordinator()
        coord.syncFull()

        assertEquals(listOf("SNES"), db.platforms().map { it.displayName })
        assertEquals(2, db.gamesCount(1, null))
        // cursor = max updated_at across platforms + roms
        assertEquals("2024-03-03T00:00:00", db.getSyncState("cursor"))
        // progress lands at completed == total (one supported platform), labelled for the finishing phase
        assertEquals(RommSyncCoordinator.SyncProgress(1, 1, "Collections"), coord.progress.value)
    }

    @Test fun `delta sweep upserts changed roms and advances cursor`() = runBlocking {
        db.replacePlatforms(listOf())
        db.setSyncState("cursor", "2024-01-01T00:00:00")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/api/platforms/identifiers") -> json("[1]")
                    path.startsWith("/api/platforms") -> {
                        assertFalse(path.contains("updated_after"))
                        json("""[{"id":1,"slug":"snes","rom_count":1,"display_name":"SNES","updated_at":"2024-04-04T00:00:00"}]""")
                    }
                    path.startsWith("/api/roms/identifiers") -> json("[10]")
                    path.startsWith("/api/roms") -> json("""{"items":[
                        {"id":10,"platform_id":1,"fs_name":"a.sfc","name":"Alpha","updated_at":"2024-05-05T00:00:00"}
                    ],"total":1,"limit":100,"offset":0}""")
                    path.startsWith("/api/collections") -> json("[]")
                    else -> json("{}")
                }
            }
        }

        coordinator().syncDelta()

        assertEquals(1, db.gamesCount(1, null))
        assertEquals("2024-05-05T00:00:00", db.getSyncState("cursor"))
    }

    @Test fun `delta imports new rom when its existing platform is unchanged`() = runBlocking {
        db.replacePlatforms(
            listOf(
                RommPlatform(1, "snes", "SNES", "SNES", romCount = 0) to
                    "2024-01-01T00:00:00",
            ),
        )
        db.setSyncState("cursor", "2024-01-01T00:00:00")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/api/platforms/identifiers") -> json("[1]")
                    // The full catalog includes the platform even though its updated_at is old.
                    path.startsWith("/api/platforms") -> json(
                        """[{"id":1,"slug":"snes","rom_count":1,"display_name":"SNES","updated_at":"2024-01-01T00:00:00"}]""",
                    )
                    path.startsWith("/api/roms/identifiers") -> json("[10]")
                    path.startsWith("/api/roms") -> json(
                        """{"items":[
                            {"id":10,"platform_id":1,"fs_name":"new.sfc","name":"New Game","updated_at":"2024-05-05T00:00:00"}
                        ],"total":1,"limit":100,"offset":0}""",
                    )
                    path.startsWith("/api/collections") -> json("[]")
                    else -> json("[]")
                }
            }
        }

        coordinator().syncDelta()

        assertEquals(setOf(10), db.cachedGameIds(1))
        assertEquals("2024-05-05T00:00:00", db.getSyncState("cursor"))
    }

    @Test fun `delta repairs a rom skipped by an older cursor`() = runBlocking {
        db.replacePlatforms(
            listOf(
                RommPlatform(1, "snes", "SNES", "SNES", romCount = 0) to
                    "2024-01-01T00:00:00",
            ),
        )
        db.setSyncState("cursor", "2024-06-06T00:00:00")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/api/platforms/identifiers") -> json("[1]")
                    path.startsWith("/api/platforms") -> json(
                        """[{"id":1,"slug":"snes","rom_count":1,"display_name":"SNES","updated_at":"2024-01-01T00:00:00"}]""",
                    )
                    path.startsWith("/api/roms/identifiers") -> json("[10]")
                    path.startsWith("/api/roms") && path.contains("updated_after") ->
                        json("""{"items":[],"total":0,"limit":100,"offset":0}""")
                    path.startsWith("/api/roms") -> json(
                        """{"items":[
                            {"id":10,"platform_id":1,"fs_name":"missed.sfc","name":"Missed Game","updated_at":"2024-05-05T00:00:00"}
                        ],"total":1,"limit":100,"offset":0}""",
                    )
                    path.startsWith("/api/collections") -> json("[]")
                    else -> json("[]")
                }
            }
        }

        coordinator().syncDelta()

        assertEquals(setOf(10), db.cachedGameIds(1))
        assertEquals("2024-06-06T00:00:00", db.getSyncState("cursor"))
    }

    @Test fun `delta reconciles deletions when cached count exceeds server rom_count`() = runBlocking {
        // Seed two cached games for platform 1; server now reports rom_count = 1 and only returns id 10 on re-pull.
        db.replacePlatforms(listOf())
        db.upsertGames(listOf(
            GameRecord(dev.cannoli.scorza.romm.RommGame(10, 1, "Alpha", "a.sfc", 0, null, null, emptyList(), emptyList(), null, emptyList()), "2024-01-01T00:00:00"),
            GameRecord(dev.cannoli.scorza.romm.RommGame(99, 1, "Ghost", "ghost.sfc", 0, null, null, emptyList(), emptyList(), null, emptyList()), "2024-01-01T00:00:00"),
        ))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/api/platforms") -> json("""[{"id":1,"slug":"snes","rom_count":1,"display_name":"SNES","updated_at":"2024-04-04T00:00:00"}]""")
                    // delta sweep (has updated_after) returns nothing changed; re-pull (no updated_after) returns the survivor.
                    path.startsWith("/api/roms") && path.contains("updated_after") ->
                        json("""{"items":[],"total":0,"limit":100,"offset":0}""")
                    path.startsWith("/api/roms") ->
                        json("""{"items":[{"id":10,"platform_id":1,"fs_name":"a.sfc","name":"Alpha","updated_at":"2024-01-01T00:00:00"}],"total":1,"limit":100,"offset":0}""")
                    else -> json("{}")
                }
            }
        }

        coordinator().syncDelta()

        assertEquals(setOf(10), db.cachedGameIds(1))
    }

    @Test fun `sync failure sets ERROR status and does not throw`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(500)
        }
        val coord = coordinator()
        coord.syncFull()
        assertEquals(RommSyncCoordinator.SyncStatus.ERROR, coord.status.value)
    }
}
