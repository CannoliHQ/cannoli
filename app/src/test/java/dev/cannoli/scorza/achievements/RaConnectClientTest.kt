package dev.cannoli.scorza.achievements

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val res = c.validateToken("bob", "tok")
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

    @Test
    fun loginWithPassword_postsPasswordAndReturnsTheToken() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true,"User":"Bob","Token":"abc123","Score":4200}"""))
        server.start()

        val result = client(server).loginWithPassword("bob", "hunter2")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("r=login"))
        assertFalse(body.contains("r=login2"))
        assertTrue(body.contains("u=bob"))
        assertTrue(body.contains("p=hunter2"))
        assertTrue(result is RaConnectClient.LoginResult.Success)
        val success = result as RaConnectClient.LoginResult.Success
        assertEquals("abc123", success.token)
        assertEquals("Bob", success.username)
        assertEquals(4200, success.score)
        server.shutdown()
    }

    @Test
    fun loginWithPassword_reportsInvalidCredentialsWithTheServerMessage() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":false,"Error":"Invalid User/Password combination."}"""))
        server.start()

        val result = client(server).loginWithPassword("bob", "wrong")

        assertTrue(result is RaConnectClient.LoginResult.InvalidCredentials)
        assertEquals(
            "Invalid User/Password combination.",
            (result as RaConnectClient.LoginResult.InvalidCredentials).message,
        )
        server.shutdown()
    }

    @Test
    fun loginWithPassword_reportsNetworkFailureSeparatelyFromBadCredentials() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString().trimEnd('/')
        server.shutdown()
        val c = RaConnectClient(baseUrlProvider = { url }, clientProvider = { OkHttpClient() }, userAgent = "x")
        assertEquals(RaConnectClient.LoginResult.NetworkError, c.loginWithPassword("bob", "hunter2"))
    }

    @Test
    fun loginWithPassword_treatsAServerErrorAsNetworkNotCredentials() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        server.start()
        assertEquals(
            RaConnectClient.LoginResult.NetworkError,
            client(server).loginWithPassword("bob", "hunter2"),
        )
        server.shutdown()
    }

    @Test
    fun loginWithPassword_treatsMalformedJsonAsNetworkError() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not json at all"))
        server.start()
        assertEquals(
            RaConnectClient.LoginResult.NetworkError,
            client(server).loginWithPassword("bob", "hunter2"),
        )
        server.shutdown()
    }

    @Test
    fun validateToken_stillPostsTheLogin2Request() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        server.start()
        client(server).validateToken("bob", "tok")
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("r=login2"))
        assertTrue(body.contains("t=tok"))
        server.shutdown()
    }
}
