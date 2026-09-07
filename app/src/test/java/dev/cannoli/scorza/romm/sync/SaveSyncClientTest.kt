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

    /** Every sync added a row and nothing removed one, so the server grew without bound. */
    @Test fun upload_prunes_the_autosave_bucket() {
        server.enqueue(MockResponse().setBody("""{"id":100,"slot":"autosave"}"""))
        val f = tmp.newFile("A.srm").apply { writeBytes("S".toByteArray()) }

        client.uploadSave(42, "snes9x", "autosave", "dev-1", false, f, pruneHistory = true)

        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("autocleanup=true"))
        assertTrue(path, path.contains("autocleanup_limit=15"))
    }

    /**
     * A named slot is a save somebody chose to keep. Pruning is for the bucket that rewrites itself
     * on every launch, which is what Argosy does too.
     */
    @Test fun upload_never_prunes_a_named_slot() {
        server.enqueue(MockResponse().setBody("""{"id":100,"slot":"before boss"}"""))
        val f = tmp.newFile("D.srm").apply { writeBytes("S".toByteArray()) }

        client.uploadSave(42, "snes9x", "before boss", "dev-1", false, f)

        val path = server.takeRequest().path!!
        assertTrue(path, !path.contains("autocleanup"))
    }

    /** The server counts what a session actually did, but only if the upload names the session. */
    @Test fun upload_names_the_sync_session_when_it_has_one() {
        server.enqueue(MockResponse().setBody("""{"id":100,"slot":"autosave"}"""))
        val f = tmp.newFile("B.srm").apply { writeBytes("S".toByteArray()) }

        client.uploadSave(42, "snes9x", "autosave", "dev-1", false, f, sessionId = 7)

        assertTrue(server.takeRequest().path!!.contains("session_id=7"))
    }

    @Test fun upload_omits_the_session_when_there_is_none() {
        server.enqueue(MockResponse().setBody("""{"id":100,"slot":"autosave"}"""))
        val f = tmp.newFile("C.srm").apply { writeBytes("S".toByteArray()) }

        client.uploadSave(42, "snes9x", "autosave", "dev-1", false, f)

        assertTrue(!server.takeRequest().path!!.contains("session_id"))
    }

    /** The server filters by slot, so a rom's whole save history need not come back to be dropped. */
    @Test fun getSaves_scopes_to_a_slot_when_one_is_named() {
        server.enqueue(MockResponse().setBody("[]"))
        client.getSaves(42, "dev-1", "autosave")
        assertTrue(server.takeRequest().path!!.contains("slot=autosave"))

        server.enqueue(MockResponse().setBody("[]"))
        client.getSaves(42, "dev-1")
        assertTrue(!server.takeRequest().path!!.contains("slot="))
    }

    /** Server-side sync state was written on every download and never read back. */
    @Test fun a_save_carries_the_server_record_of_this_device() {
        server.enqueue(MockResponse().setBody("""[{"id":100,"rom_id":42,"slot":"autosave","origin_device_id":"deck",
            "device_syncs":[{"device_id":"deck","device_name":"Steam Deck","last_synced_at":"2026-09-01T10:00:00+00:00","is_untracked":false,"is_current":false},
                            {"device_id":"dev-1","device_name":"Nova","last_synced_at":"2026-09-02T10:00:00+00:00","is_untracked":true,"is_current":true}]}]"""))

        val save = client.getSaves(42, "dev-1").single()

        assertEquals("Steam Deck", save.originDeviceName())
        assertTrue(save.isUntrackedOn("dev-1"))
        assertTrue(!save.isUntrackedOn("deck"))
        assertEquals("2026-09-02T10:00:00+00:00", save.syncFor("dev-1")?.lastSyncedAt)
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
