package dev.cannoli.scorza.romm

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RommHttpTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `injects bearer header when token present`() {
        server.enqueue(MockResponse().setBody("ok"))
        var token: String? = "tok-1"
        val http = RommHttp(tokenProvider = { token }, allowSelfSignedProvider = { false })
        http.client().newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertEquals("Bearer tok-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `omits bearer header when token absent`() {
        server.enqueue(MockResponse().setBody("ok"))
        val http = RommHttp(tokenProvider = { null }, allowSelfSignedProvider = { false })
        http.client().newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `same client instance reused when self-signed flag unchanged`() {
        val http = RommHttp(tokenProvider = { null }, allowSelfSignedProvider = { false })
        assertEquals(http.client(), http.client())
    }

    @Test fun `client is rebuilt when self-signed flag changes`() {
        var selfSigned = false
        val http = RommHttp(tokenProvider = { null }, allowSelfSignedProvider = { selfSigned })
        val first = http.client()
        selfSigned = true
        val second = http.client()
        assertNotEquals(first, second)
    }

    @Test fun `download client keeps a long read timeout while api client stays short`() {
        val http = RommHttp(tokenProvider = { null }, allowSelfSignedProvider = { false })
        assertEquals(30_000, http.client().readTimeoutMillis)
        assertEquals(600_000, http.downloadClient().readTimeoutMillis)
        assertEquals(15_000, http.downloadClient().connectTimeoutMillis)
    }

    @Test fun `download client is reused until the base client changes`() {
        var selfSigned = false
        val http = RommHttp(tokenProvider = { null }, allowSelfSignedProvider = { selfSigned })
        val first = http.downloadClient()
        assertEquals(first, http.downloadClient())
        selfSigned = true
        assertNotEquals(first, http.downloadClient())
    }
}
