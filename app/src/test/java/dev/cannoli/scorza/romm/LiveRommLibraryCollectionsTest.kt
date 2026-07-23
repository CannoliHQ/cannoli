package dev.cannoli.scorza.romm

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveRommLibraryCollectionsTest {

    private val slugMap = RommSlugMap.parse("""{}""")
    private val platformMap = PlatformMap(slugMap, isSupported = { true })

    private fun client(server: MockWebServer): RommClient {
        val ok = OkHttpClient()
        return RommClient(baseUrlProvider = { server.url("/").toString().trimEnd('/') }, clientProvider = { ok })
    }

    @Test
    fun collections_fetchesEnabledGroupsOnly() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""[{"id":1,"name":"Faves","rom_ids":[10],"rom_count":1}]"""))
        server.start()
        try {
            val lib = LiveRommLibrary(client(server), platformMap)
            val result = lib.collections(setOf(RommCollectionGroup.USER))

            assertEquals("/api/collections", server.takeRequest().path)
            assertEquals(listOf("1"), result.map { it.id })
            assertEquals(RommCollectionGroup.USER, result[0].group)
            assertEquals("Faves", result[0].name)
            assertEquals(1, result[0].romCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun collections_skipsGroupsNotRequested() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""[{"id":"snes-rpgs","name":"SNES RPGs","rom_ids":[11],"rom_count":1}]"""))
        server.start()
        try {
            val lib = LiveRommLibrary(client(server), platformMap)
            val result = lib.collections(setOf(RommCollectionGroup.VIRTUAL))

            assertEquals("/api/collections/virtual?type=all", server.takeRequest().path)
            assertEquals(1, result.size)
            assertEquals(RommCollectionGroup.VIRTUAL, result[0].group)
        } finally {
            server.shutdown()
        }
    }
}
