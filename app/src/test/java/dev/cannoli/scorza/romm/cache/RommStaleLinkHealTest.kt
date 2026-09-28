package dev.cannoli.scorza.romm.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.PlatformMap
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.RommSlugMap
import dev.cannoli.scorza.romm.sync.RommCacheMatcher
import dev.cannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RommStaleLinkHealTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var cache: RommDatabase
    private lateinit var links: RommLinkRepository
    private lateinit var matcher: RommCacheMatcher

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val root = tmp.newFolder("cannoli")
        val romDir = File(root, "Roms").apply { mkdirs() }
        val config = File(root, "Config").apply { mkdirs() }
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = root.absolutePath
        links = RommLinkRepository(CannoliDatabase(CannoliPathsProvider(settings))) { romDir }
        cache = RommDatabase { File(config, "romm.db") }
        matcher = RommCacheMatcher(cache)

        cache.replacePlatforms(listOf(RommPlatform(1, "nes", "NES", "NES", 0) to null))
        cache.upsertGames(listOf(game(10, "Tecmo.nes"), game(20, "Kept.nes")))
        cache.setSyncState("cursor", "2024-01-01T00:00:00")
        links.upsertLink(10, "NES/Tecmo.nes", "download")
        links.upsertLink(20, "NES/Kept.nes", "download")
    }

    @After fun tearDown() { server.shutdown(); cache.close() }

    private fun game(id: Int, fsName: String) =
        GameRecord(RommGame(id, 1, fsName, fsName, 0, null, null, emptyList(), emptyList(), null, emptyList()), "2024-01-01T00:00:00")

    private fun coordinator(): RommSyncCoordinator {
        val client = RommClient({ server.url("/").toString().trimEnd('/') }, { OkHttpClient() })
        val platformMap = PlatformMap(RommSlugMap.parse("""{"nes":"NES"}""")) { true }
        return RommSyncCoordinator(client, platformMap, cache, links = links, onCacheChanged = { matcher.refresh() })
    }

    private fun json(body: String) = MockResponse().setBody(body)

    // Tecmo moved from id 10 to id 11 on the server; Kept is untouched.
    private fun serve(romIdentifiers: MockResponse = json("[11,20]")) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/api/platforms/identifiers") -> json("[1]")
                    path.startsWith("/api/platforms") -> json("""[{"id":1,"slug":"nes","rom_count":2,"display_name":"NES","updated_at":"2024-01-01T00:00:00"}]""")
                    path.startsWith("/api/roms/identifiers") -> romIdentifiers
                    path.startsWith("/api/roms") && path.contains("updated_after") -> json("""{"items":[
                        {"id":11,"platform_id":1,"fs_name":"Tecmo.nes","name":"Tecmo","updated_at":"2024-02-02T00:00:00"}
                    ],"total":1,"limit":100,"offset":0}""")
                    path.startsWith("/api/roms") -> json("""{"items":[
                        {"id":11,"platform_id":1,"fs_name":"Tecmo.nes","name":"Tecmo","updated_at":"2024-02-02T00:00:00"},
                        {"id":20,"platform_id":1,"fs_name":"Kept.nes","name":"Kept","updated_at":"2024-01-01T00:00:00"}
                    ],"total":2,"limit":100,"offset":0}""")
                    else -> json("[]")
                }
            }
        }
    }

    @Test fun `a link to a vanished id is dropped and the filename match finds the new id`() = runBlocking {
        serve()
        assertEquals(10, matcher.rommIdFor("NES", "Tecmo.nes"))

        coordinator().syncDelta()

        assertNull(links.rommIdForPath("NES/Tecmo.nes"))
        assertEquals(11, matcher.rommIdFor("NES", "Tecmo.nes"))
    }

    @Test fun `a link whose id still exists is kept`() = runBlocking {
        serve()

        coordinator().syncDelta()

        assertEquals(20, links.rommIdForPath("NES/Kept.nes"))
    }

    @Test fun `a link under a platform the cache does not hold is kept`() = runBlocking {
        links.upsertLink(30, "SNES/Other.sfc", "download")
        serve()

        coordinator().syncDelta()

        assertEquals(30, links.rommIdForPath("SNES/Other.sfc"))
    }

    @Test fun `nothing is dropped when the id list cannot be fetched`() = runBlocking {
        serve(romIdentifiers = MockResponse().setResponseCode(500))

        coordinator().syncDelta()

        assertEquals(10, links.rommIdForPath("NES/Tecmo.nes"))
        assertEquals(20, links.rommIdForPath("NES/Kept.nes"))
    }

    @Test fun `nothing is dropped when the refresh fails`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(500)
        }

        val coord = coordinator()
        coord.syncDelta()

        assertEquals(RommSyncCoordinator.SyncStatus.ERROR, coord.status.value)
        assertEquals(10, links.rommIdForPath("NES/Tecmo.nes"))
        assertEquals(20, links.rommIdForPath("NES/Kept.nes"))
    }
}
