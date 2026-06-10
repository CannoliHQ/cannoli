package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RommDtosTest {

    @Test fun `parses token exchange response and ignores unknown fields`() {
        val json = """
            {"id":7,"name":"Cannoli","scopes":["roms:read"],"expires_at":null,
             "last_used_at":null,"created_at":"2026-06-09T00:00:00Z","user_id":3,
             "raw_token":"abc123"}
        """.trimIndent()
        val dto = rommJson.decodeFromString(ClientTokenDto.serializer(), json)
        assertEquals("abc123", dto.rawToken)
        assertEquals("Cannoli", dto.name)
    }

    @Test fun `parses platform list entry`() {
        val json = """
            {"id":12,"slug":"snes","fs_slug":"snes","rom_count":42,
             "name":"Super Nintendo","display_name":"Super Nintendo","extra":"ignored"}
        """.trimIndent()
        val dto = rommJson.decodeFromString(PlatformDto.serializer(), json)
        assertEquals("snes", dto.slug)
        assertEquals(42, dto.romCount)
        assertEquals("Super Nintendo", dto.displayName)
    }

    @Test fun `parses roms page with files`() {
        val json = """
            {"items":[
               {"id":100,"platform_id":12,"platform_slug":"snes",
                "fs_name":"Game (USA).sfc","fs_name_no_ext":"Game (USA)","fs_extension":"sfc",
                "fs_size_bytes":1048576,"name":"Game","summary":"A game.","revision":null,
                "regions":["USA"],"languages":["en"],"crc_hash":"DEADBEEF","md5_hash":null,
                "sha1_hash":null,"path_cover_large":"assets/roms/snes/100/cover.png",
                "url_cover":null,"has_multiple_files":false,
                "files":[{"id":1,"file_name":"Game (USA).sfc","file_size_bytes":1048576,
                          "crc_hash":"DEADBEEF","md5_hash":null,"sha1_hash":null}]}
             ],"total":1,"limit":100,"offset":0}
        """.trimIndent()
        val page = rommJson.decodeFromString(RomsPageDto.serializer(), json)
        assertEquals(1, page.total)
        assertEquals(1, page.items.size)
        val rom = page.items.first()
        assertEquals("Game (USA).sfc", rom.fsName)
        assertEquals(1, rom.files.size)
        assertEquals(1048576L, rom.files.first().fileSizeBytes)
        assertTrue(rom.regions.contains("USA"))
    }
}
