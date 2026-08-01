package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.PlatformMap
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommSlugMap
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // cursor = max rom updated_at
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
                    path.startsWith("/api/platforms") -> {
                        assertTrue(!path.contains("updated_after"))
                        json("""[{"id":1,"slug":"snes","rom_count":1,"display_name":"SNES","updated_at":"2024-04-04T00:00:00"}]""")
                    }
                    path.startsWith("/api/roms") -> json("""{"items":[
                        {"id":10,"platform_id":1,"fs_name":"a.sfc","name":"Alpha","updated_at":"2024-05-05T00:00:00"}
                    ],"total":1,"limit":100,"offset":0}""")
                    else -> json("{}")
                }
            }
        }

        coordinator().syncDelta()

        assertEquals(1, db.gamesCount(1, null))
        assertEquals("2024-05-05T00:00:00", db.getSyncState("cursor"))
    }

    // RomM leaves a platform's updated_at alone when a rom under it changes, so the platform must
    // still be in the supported set the sweep filters against or its roms get thrown away.
    @Test fun `delta stores roms whose platform is older than the cursor`() = runBlocking {
        db.replacePlatforms(listOf())
        db.setSyncState("cursor", "2024-06-01T00:00:00")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    // The server honours updated_after, and this platform predates the cursor.
                    path.startsWith("/api/platforms") && path.contains("updated_after") -> json("[]")
                    path.startsWith("/api/platforms") -> json("""[{"id":1,"slug":"snes","rom_count":1,"display_name":"SNES","updated_at":"2024-01-01T00:00:00"}]""")
                    path.startsWith("/api/roms") -> json("""{"items":[
                        {"id":10,"platform_id":1,"fs_name":"a.sfc","name":"Alpha","updated_at":"2024-07-07T00:00:00"}
                    ],"total":1,"limit":100,"offset":0}""")
                    else -> json("{}")
                }
            }
        }

        coordinator().syncDelta()

        assertEquals(setOf(10), db.cachedGameIds(1))
        assertEquals("2024-07-07T00:00:00", db.getSyncState("cursor"))
    }

    @Test fun `cursor advances from rom timestamps only`() = runBlocking {
        db.replacePlatforms(listOf())
        db.setSyncState("cursor", "2024-01-01T00:00:00")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    // Platform stamped well after every rom; letting it feed the cursor would skip
                    // everything updated in between on the next delta.
                    path.startsWith("/api/platforms") -> json("""[{"id":1,"slug":"snes","rom_count":1,"display_name":"SNES","updated_at":"2024-09-09T00:00:00"}]""")
                    path.startsWith("/api/roms") -> json("""{"items":[
                        {"id":10,"platform_id":1,"fs_name":"a.sfc","name":"Alpha","updated_at":"2024-05-05T00:00:00"}
                    ],"total":1,"limit":100,"offset":0}""")
                    else -> json("{}")
                }
            }
        }

        coordinator().syncDelta()

        assertEquals("2024-05-05T00:00:00", db.getSyncState("cursor"))
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

    @Test fun `stale is set by a failed sync and cleared by the next successful one`() = runBlocking {
        var fail = true
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (fail) return MockResponse().setResponseCode(500)
                val path = request.path!!
                return when {
                    path.startsWith("/api/platforms") -> json("""[{"id":1,"slug":"snes","rom_count":1,"display_name":"SNES","updated_at":"2024-01-01T00:00:00"}]""")
                    path.startsWith("/api/roms") -> json("""{"items":[
                        {"id":10,"platform_id":1,"fs_name":"a.sfc","name":"Alpha","updated_at":"2024-05-05T00:00:00"}
                    ],"total":1,"limit":100,"offset":0}""")
                    else -> json("{}")
                }
            }
        }

        val coord = coordinator()
        assertEquals(false, coord.stale.value)

        coord.syncDelta()
        assertEquals(true, coord.stale.value)

        fail = false
        coord.syncDelta()
        assertEquals(false, coord.stale.value)
    }
}
