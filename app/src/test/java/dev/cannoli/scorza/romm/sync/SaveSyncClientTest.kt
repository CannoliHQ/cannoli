package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommHttp
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveSyncClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: RommClient

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val http = RommHttp(tokenProvider = { "tok" }, allowSelfSignedProvider = { false })
        client = RommClient(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            clientProvider = { http.client() },
        )
    }

    @After fun teardown() { server.shutdown() }

    @Test fun registerDevice_posts_to_api_devices() {
        server.enqueue(MockResponse().setBody("""{"device_id":"uuid-9","name":"Odin","created_at":"x"}"""))
        val r = client.registerDevice(DeviceRegisterPayload(name = "Odin", clientVersion = "1.8.0"))
        assertEquals("uuid-9", r.deviceId)
        val req = server.takeRequest()
        assertEquals("/api/devices", req.path)
        assertEquals("POST", req.method)
        assertTrue(req.getHeader("Authorization") == "Bearer tok")
    }

    @Test fun negotiate_posts_inventory() {
        server.enqueue(MockResponse().setBody("""{"session_id":3,"operations":[],"total_upload":0,"total_download":0,"total_conflict":0,"total_no_op":1}"""))
        val r = client.negotiateSync(SyncNegotiatePayload("dev-1", listOf(ClientSaveState(42, "Mario.srm", "autosave", "snes9x", "h", "2026-06-26T00:00:00Z", 8192))))
        assertEquals(3, r.sessionId)
        assertEquals("/api/sync/negotiate", server.takeRequest().path)
    }

    @Test fun upload_is_multipart_with_query_params() {
        server.enqueue(MockResponse().setBody("""{"id":100,"slot":"autosave","content_hash":"abc"}"""))
        val f = tmp.newFile("Mario.srm").apply { writeBytes("SRAM".toByteArray()) }
        val saved = client.uploadSave(romId = 42, emulator = "snes9x", slot = "autosave", deviceId = "dev-1", overwrite = false, file = f)
        assertEquals(100, saved.id)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/saves?"))
        assertTrue(req.path!!.contains("rom_id=42"))
        assertTrue(req.path!!.contains("slot=autosave"))
        assertTrue(req.path!!.contains("device_id=dev-1"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=\"saveFile\""))
    }

    @Test fun confirmSaveDownloaded_posts_device_id_as_json() {
        server.enqueue(MockResponse().setBody("""{"id":100,"rom_id":42,"file_name":"Mario.srm","file_size_bytes":8192,"updated_at":"2026-06-27T00:00:00Z"}"""))
        val result = client.confirmSaveDownloaded(saveId = 100, deviceId = "dev-1")
        assertEquals(100, result.id)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/saves/100/downloaded"))
        assertEquals("POST", req.method)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"device_id\""))
        assertTrue(body.contains("dev-1"))
    }

    @Test fun download_streams_content_to_file() {
        server.enqueue(MockResponse().setBody("RAWSAVEBYTES"))
        val dest = tmp.newFile("out.srm")
        client.downloadSaveContent(saveId = 100, deviceId = "dev-1", dest = dest)
        assertEquals("RAWSAVEBYTES", dest.readText())
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/saves/100/content?"))
        assertTrue(req.path!!.contains("optimistic=false"))
    }
}
