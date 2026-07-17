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

class RommClientDeviceAuthTest {

    private lateinit var server: MockWebServer
    private val okhttp = OkHttpClient()
    private fun client() = RommClient({ server.url("/").toString().trimEnd('/') }, { okhttp })

    private fun initPayload() = DeviceAuthInitPayload(
        clientDeviceIdentifier = "and-1",
        name = "Retroid Pocket 5",
        client = "Cannoli",
        platform = "android",
        clientVersion = "1.8.0",
        requestedScopes = listOf("roms.read"),
    )

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `init posts payload and parses response`() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"device_code":"dc-1","user_code":"ABCD2345","verification_path":"/pair/device","verification_path_complete":"/pair/device?user_code=ABCD2345","expires_in":600,"interval":5}"""
            )
        )
        val dto = client().deviceAuthInit(initPayload())
        assertEquals("dc-1", dto.deviceCode)
        assertEquals("ABCD2345", dto.userCode)
        assertEquals("/pair/device?user_code=ABCD2345", dto.verificationPathComplete)
        assertEquals(600, dto.expiresIn)
        assertEquals(5, dto.interval)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/auth/device/init", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"client_device_identifier\":\"and-1\""))
        assertTrue(body.contains("\"client\":\"Cannoli\""))
        assertTrue(body.contains("\"requested_scopes\":[\"roms.read\"]"))
    }

    @Test fun `token returns dto on 200`() {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"tok-1","device_id":"dev-9","scopes":["roms.read"],"expires_at":null}"""
            )
        )
        val dto = client().deviceAuthToken("dc-1")
        assertEquals("tok-1", dto.accessToken)
        assertEquals("dev-9", dto.deviceId)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/auth/device/token", req.path)
        assertTrue(req.body.readUtf8().contains("\"device_code\":\"dc-1\""))
    }

    @Test fun `token 400 carries detail in exception message`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"authorization_pending"}"""))
        try {
            client().deviceAuthToken("dc-1")
            fail("expected RommException")
        } catch (e: RommException) {
            assertEquals(400, e.statusCode)
            assertTrue(e.message!!.contains("authorization_pending"))
        }
    }

    @Test fun `non-2xx init throws RommException with status code`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        try {
            client().deviceAuthInit(initPayload())
            fail("expected RommException")
        } catch (e: RommException) {
            assertEquals(429, e.statusCode)
        }
    }

    @Test fun `malformed json on 200 throws RommException with status code`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        try {
            client().deviceAuthToken("dc-1")
            fail("expected RommException")
        } catch (e: RommException) {
            assertEquals(200, e.statusCode)
            assertTrue(e.message!!.contains("Parse error"))
        }
    }

    @Test fun `malformed base url throws RommException`() {
        val bad = RommClient({ "not a url" }, { OkHttpClient() })
        try {
            bad.deviceAuthToken("dc-1")
            fail("expected RommException")
        } catch (e: RommException) {
            assertNull(e.statusCode)
            assertTrue(e.message!!.contains("Invalid server URL"))
        }
    }
}
