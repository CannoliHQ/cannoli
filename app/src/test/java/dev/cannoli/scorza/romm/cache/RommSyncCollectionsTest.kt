package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.PlatformMap
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommCollectionGroup
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

class RommSyncCollectionsTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var db: RommDatabase

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        db = RommDatabase { File(tmp.newFolder("Config"), "romm.db") }
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    private fun json(body: String) = MockResponse().setBody(body)

    private fun coordinator(groups: Set<RommCollectionGroup> = setOf(RommCollectionGroup.USER)): RommSyncCoordinator {
        val client = RommClient({ server.url("/").toString().trimEnd('/') }, { OkHttpClient() })
        val platformMap = PlatformMap(RommSlugMap.parse("""{"snes":"SNES"}""")) { true }
        return RommSyncCoordinator(client, platformMap, db, enabledGroups = { groups })
    }

    private fun platformsResponse() =
        """[{"id":1,"slug":"snes","rom_count":1,"display_name":"SNES","updated_at":"2024-01-01T00:00:00"}]"""

    private fun romsResponse() =
        """{"items":[{"id":10,"platform_id":1,"fs_name":"a.sfc","name":"Alpha","updated_at":"2024-01-01T00:00:00"}],"total":1,"limit":100,"offset":0}"""

    @Test fun `syncFull stores a user collection and its members`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/api/platforms/identifiers") -> json("[]")
                    path.startsWith("/api/platforms") -> json(platformsResponse())
                    path.startsWith("/api/roms/identifiers") -> json("[]")
                    path.startsWith("/api/roms") -> json(romsResponse())
                    path.startsWith("/api/collections") -> json(
                        """[{"id":42,"name":"Favourites","rom_ids":[10],"rom_count":1}]"""
                    )
                    else -> json("[]")
                }
            }
        }

        coordinator().syncFull()

        val collections = db.collections(setOf(RommCollectionGroup.USER))
        assertEquals(1, collections.size)
        assertEquals("42", collections[0].id)
        assertEquals("Favourites", collections[0].name)
        assertEquals(RommCollectionGroup.USER, collections[0].group)
        assertEquals(1, collections[0].romCount)
    }

    @Test fun `syncFull reconciles collections removed from the server`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/api/platforms/identifiers") -> json("[]")
                    path.startsWith("/api/platforms") -> json(platformsResponse())
                    path.startsWith("/api/roms/identifiers") -> json("[]")
                    path.startsWith("/api/roms") -> json(romsResponse())
                    path.startsWith("/api/collections") -> json(
                        """[{"id":42,"name":"Favourites","rom_ids":[10],"rom_count":1}]"""
                    )
                    else -> json("[]")
                }
            }
        }

        coordinator().syncFull()
        assertEquals(1, db.collections(setOf(RommCollectionGroup.USER)).size)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/api/platforms/identifiers") -> json("[]")
                    path.startsWith("/api/platforms") -> json(platformsResponse())
                    path.startsWith("/api/roms/identifiers") -> json("[]")
                    path.startsWith("/api/roms") -> json(romsResponse())
                    path.startsWith("/api/collections") -> json("[]")
                    else -> json("[]")
                }
            }
        }

        coordinator().syncFull()

        assertTrue(db.collections(setOf(RommCollectionGroup.USER)).isEmpty())
        assertTrue(db.allCollectionIds().isEmpty())
    }

    @Test fun `collection sync failure does not abort platform and game sync`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/api/platforms/identifiers") -> json("[]")
                    path.startsWith("/api/platforms") -> json(platformsResponse())
                    path.startsWith("/api/roms/identifiers") -> json("[]")
                    path.startsWith("/api/roms") -> json(romsResponse())
                    path.startsWith("/api/collections") -> MockResponse().setResponseCode(500)
                    else -> json("[]")
                }
            }
        }

        val coord = coordinator()
        coord.syncFull()

        assertEquals(RommSyncCoordinator.SyncStatus.IDLE, coord.status.value)
        assertEquals(1, db.gamesCount(1, null))
    }
}
