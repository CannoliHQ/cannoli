package dev.cannoli.scorza.romm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class RommClientCollectionsTest {
    private fun client(server: MockWebServer): RommClient {
        val ok = OkHttpClient()
        return RommClient(baseUrlProvider = { server.url("/").toString().trimEnd('/') }, clientProvider = { ok })
    }

    @Test
    fun getCollections_parsesUserGroup() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            [{"id":7,"name":"Favorites","rom_ids":[1,2,3],"rom_count":3}]
        """.trimIndent()))
        server.start()
        val result = client(server).getCollections(RommCollectionGroup.USER)
        assertEquals(1, result.size)
        assertEquals("7", result[0].id)
        assertEquals("Favorites", result[0].name)
        assertEquals(listOf(1, 2, 3), result[0].romIds)
        server.shutdown()
    }

    @Test
    fun getCollections_virtualSendsTypeAllAndParsesStringId() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            [{"id":"genre/rpg","name":"RPG","rom_ids":[4,5],"rom_count":2}]
        """.trimIndent()))
        server.start()
        val result = client(server).getCollections(RommCollectionGroup.VIRTUAL)
        val request = server.takeRequest()
        assertEquals("/api/collections/virtual", request.requestUrl?.encodedPath)
        assertEquals("all", request.requestUrl?.queryParameter("type"))
        assertEquals(1, result.size)
        assertEquals("genre/rpg", result[0].id)
        assertEquals(listOf(4, 5), result[0].romIds)
        server.shutdown()
    }
}
