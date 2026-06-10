package dev.cannoli.scorza.romm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RommClientLibraryTest {

    private lateinit var server: MockWebServer
    private val okhttp = OkHttpClient()
    private fun client() = RommClient({ server.url("/").toString().trimEnd('/') }, { okhttp })

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `getPlatforms parses list`() {
        server.enqueue(MockResponse().setBody(
            """[{"id":1,"slug":"snes","rom_count":3,"name":"SNES","display_name":"SNES"}]"""
        ))
        val platforms = client().getPlatforms()
        assertEquals(1, platforms.size)
        assertEquals("snes", platforms.first().slug)
        assertEquals("/api/platforms", server.takeRequest().path)
    }

    @Test fun `getRoms sends the expected query params`() {
        server.enqueue(MockResponse().setBody("""{"items":[],"total":0,"limit":100,"offset":0}"""))
        client().getRoms(platformId = 12, limit = 100, offset = 0, search = null)
        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/api/roms?"))
        assertTrue(path.contains("platform_ids=12"))
        assertTrue(path.contains("limit=100"))
        assertTrue(path.contains("offset=0"))
        assertTrue(path.contains("with_files=true"))
        assertTrue(path.contains("with_char_index=false"))
        assertTrue(path.contains("with_filter_values=false"))
        assertTrue(path.contains("order_by=name"))
        assertTrue(path.contains("order_dir=asc"))
    }

    @Test fun `getRoms adds search_term when provided`() {
        server.enqueue(MockResponse().setBody("""{"items":[],"total":0,"limit":100,"offset":0}"""))
        client().getRoms(platformId = 12, limit = 100, offset = 0, search = "mario")
        assertTrue(server.takeRequest().path!!.contains("search_term=mario"))
    }

    @Test fun `resolveBaseUrl returns the candidate that answers heartbeat`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val base = server.url("/").toString().trimEnd('/')
        assertEquals(base, client().resolveBaseUrl(base))
        assertEquals("/api/heartbeat", server.takeRequest().path)
    }

    @Test fun `resolveBaseUrl returns null when no candidate answers`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val base = server.url("/").toString().trimEnd('/')
        assertNull(client().resolveBaseUrl(base))
    }

    @Test fun `currentUser parses username`() {
        server.enqueue(MockResponse().setBody("""{"id":3,"username":"btk","email":"x@y.z"}"""))
        assertEquals("btk", client().currentUser())
        assertEquals("/api/users/me", server.takeRequest().path)
    }

    @Test fun `currentUser returns null on error`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertNull(client().currentUser())
    }

    @Test fun `serverVersion parses nested SYSTEM VERSION`() {
        server.enqueue(MockResponse().setBody("""{"SYSTEM":{"VERSION":"4.7.2","SHOW_SETUP_WIZARD":false}}"""))
        assertEquals("4.7.2", client().serverVersion())
        assertEquals("/api/heartbeat", server.takeRequest().path)
    }
}
