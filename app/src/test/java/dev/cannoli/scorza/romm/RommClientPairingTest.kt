package dev.cannoli.scorza.romm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RommClientPairingTest {

    private lateinit var server: MockWebServer
    private val okhttp = OkHttpClient()
    private fun client() = RommClient({ server.url("/").toString().trimEnd('/') }, { okhttp })

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `exchangeCode strips dash and returns raw token`() {
        server.enqueue(MockResponse().setBody("""{"name":"Cannoli","raw_token":"tok-xyz"}"""))
        val token = client().exchangeCode("ABCD-1234")
        assertEquals("tok-xyz", token)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/client-tokens/exchange", req.path)
        assertTrue(req.body.readUtf8().contains("\"ABCD1234\""))
    }

    @Test fun `non-2xx throws RommException with status code and message`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        try {
            client().exchangeCode("ABCD-1234")
            fail("expected RommException")
        } catch (e: RommException) {
            assertEquals(401, e.statusCode)
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test fun `malformed json on 200 throws RommException with status code`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        try {
            client().exchangeCode("ABCD-1234")
            fail("expected RommException")
        } catch (e: RommException) {
            assertEquals(200, e.statusCode)
            assertTrue(e.message!!.contains("Parse error"))
        }
    }

    @Test fun `malformed base url throws RommException`() {
        val bad = RommClient({ "not a url" }, { OkHttpClient() })
        try {
            bad.exchangeCode("ABCD-1234")
            fail("expected RommException")
        } catch (e: RommException) {
            assertNull(e.statusCode)
            assertTrue(e.message!!.contains("Invalid server URL"))
        }
    }
}
