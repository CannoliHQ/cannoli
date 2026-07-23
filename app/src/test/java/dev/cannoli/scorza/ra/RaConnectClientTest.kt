package dev.cannoli.scorza.ra

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaConnectClientTest {
    private fun client(server: MockWebServer): RaConnectClient {
        val ok = OkHttpClient()
        return RaConnectClient(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            clientProvider = { ok },
            userAgent = "Cannoli/test",
        )
    }

    @Test
    fun achievementSets_postsExpectedParamsToDorequest() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        server.start()

        val res = client(server).achievementSets("bob", "tok", 1234)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.endsWith("/dorequest.php"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("r=achievementsets"))
        assertTrue(body.contains("u=bob"))
        assertTrue(body.contains("t=tok"))
        assertTrue(body.contains("g=1234"))
        assertEquals(200, res.code)
        assertEquals("""{"Success":true}""", res.body)
        server.shutdown()
    }

    @Test
    fun startSession_includesGameIdAndClientVersion() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        client(server).startSession("bob", "tok", 99)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("r=startsession"))
        assertTrue(body.contains("g=99"))
        assertTrue(body.contains("l="))
        server.shutdown()
    }

    @Test
    fun networkFailure_returnsNegativeCode() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString().trimEnd('/')
        server.shutdown()
        val c = RaConnectClient(baseUrlProvider = { url }, clientProvider = { OkHttpClient() }, userAgent = "x")
        val res = c.login2("bob", "tok")
        assertTrue(res.code < 200)
    }

    @Test
    fun resolveGameId_postsHashAndParsesGameId() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true,"GameID":1234}"""))
        server.start()

        val id = client(server).resolveGameId("bob", "tok", "abcdef0123456789")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("r=gameid"))
        assertTrue(body.contains("m=abcdef0123456789"))
        assertEquals(1234, id)
        server.shutdown()
    }

    @Test
    fun resolveGameId_returnsZeroWhenUnrecognized() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true,"GameID":0}"""))
        server.start()
        assertEquals(0, client(server).resolveGameId("bob", "tok", "deadbeef"))
        server.shutdown()
    }

    @Test
    fun resolveGameId_returnsNegativeWhenServerUnreachable() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString().trimEnd('/')
        server.shutdown()
        val c = RaConnectClient(baseUrlProvider = { url }, clientProvider = { OkHttpClient() }, userAgent = "x")
        assertTrue(c.resolveGameId("bob", "tok", "abcd") < 0)
    }
}
