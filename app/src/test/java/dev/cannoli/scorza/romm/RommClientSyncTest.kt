package dev.cannoli.scorza.romm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RommClientSyncTest {

    private lateinit var server: MockWebServer
    private val okhttp = OkHttpClient()
    private fun client() = RommClient({ server.url("/").toString().trimEnd('/') }, { okhttp })

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `getPlatforms adds updated_after when provided`() {
        server.enqueue(MockResponse().setBody("[]"))
        client().getPlatforms(updatedAfter = "2024-01-01T00:00:00")
        assertTrue(server.takeRequest().path!!.contains("updated_after=2024-01-01"))
    }

    @Test fun `getPlatforms omits updated_after when null`() {
        server.enqueue(MockResponse().setBody("[]"))
        client().getPlatforms(updatedAfter = null)
        assertEquals("/api/platforms", server.takeRequest().path)
    }

    @Test fun `getRoms without platform omits platform_ids and adds updated_after`() {
        server.enqueue(MockResponse().setBody("""{"items":[],"total":0,"limit":100,"offset":0}"""))
        client().getRoms(platformId = null, limit = 100, offset = 0, search = null, updatedAfter = "2024-05-05T00:00:00")
        val path = server.takeRequest().path!!
        assertFalse(path.contains("platform_ids="))
        assertTrue(path.contains("updated_after=2024-05-05"))
    }

    @Test fun `parses updated_at on a rom`() {
        server.enqueue(MockResponse().setBody(
            """{"items":[{"id":1,"platform_id":2,"fs_name":"a.sfc","updated_at":"2024-06-06T00:00:00"}],"total":1,"limit":100,"offset":0}"""
        ))
        val page = client().getRoms(platformId = 2, limit = 100, offset = 0, search = null)
        assertEquals("2024-06-06T00:00:00", page.items.first().updatedAt)
    }
}
