package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.romm.rommJson
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveSyncDtosTest {
    @Test fun parses_negotiate_response() {
        val json = """
          {"session_id":7,"operations":[
            {"action":"download","rom_id":42,"save_id":100,"file_name":"Mario.srm","slot":"autosave","reason":"server newer","server_updated_at":"2026-06-26T00:00:00Z","server_content_hash":"abc"}
          ],"total_upload":0,"total_download":1,"total_conflict":0,"total_no_op":0}
        """.trimIndent()
        val r = rommJson.decodeFromString(SyncNegotiateResponse.serializer(), json)
        assertEquals(7, r.sessionId)
        assertEquals("download", r.operations.single().action)
        assertEquals(100, r.operations.single().saveId)
        assertEquals("autosave", r.operations.single().slot)
    }

    @Test fun serializes_negotiate_payload_with_snake_case() {
        val p = SyncNegotiatePayload(
            deviceId = "dev-1",
            saves = listOf(ClientSaveState(romId = 42, fileName = "Mario.srm", slot = "autosave", emulator = "snes9x", contentHash = "h", updatedAt = "2026-06-26T00:00:00Z", fileSizeBytes = 8192)),
        )
        val json = rommJson.encodeToString(SyncNegotiatePayload.serializer(), p)
        assertEquals(true, json.contains("\"device_id\":\"dev-1\""))
        assertEquals(true, json.contains("\"file_size_bytes\":8192"))
    }

    @Test fun parses_device_register_response() {
        val r = rommJson.decodeFromString(DeviceRegisterResponse.serializer(), """{"device_id":"uuid-1","name":"Odin","created_at":"x"}""")
        assertEquals("uuid-1", r.deviceId)
    }

    @Test fun parses_save_dto() {
        val s = rommJson.decodeFromString(RommSaveDto.serializer(), """{"id":100,"rom_id":42,"file_name":"Mario.srm","file_size_bytes":8192,"updated_at":"2026-06-26T00:00:00Z","slot":"autosave","content_hash":"abc","origin_device_id":"dev-9","download_path":"/api/raw/assets/x"}""")
        assertEquals(100, s.id)
        assertEquals("autosave", s.slot)
        assertEquals("dev-9", s.originDeviceId)
    }
}
