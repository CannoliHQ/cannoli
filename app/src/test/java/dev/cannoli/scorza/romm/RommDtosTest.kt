package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun `takes the RomM-hosted media and ignores the provider urls beside it`() {
        val json = """
            {"id":1,"platform_id":12,"fs_name":"G.sfc","name":"G",
             "path_cover_large":"assets/romm/resources/roms/snes/1/cover/big.png",
             "url_cover":"https://images.igdb.com/igdb/cover.jpg",
             "merged_screenshots":["assets/romm/resources/roms/snes/1/screenshots/0.png"],
             "has_manual":true,
             "path_manual":"assets/romm/resources/roms/snes/1/manual/1.pdf",
             "url_manual":"https://api.screenscraper.fr/api2/mediaJeu.php?media=manuel(us)",
             "ss_metadata":{
               "box3d_path":"assets/romm/resources/roms/snes/1/box3d.png",
               "box3d_url":"https://www.screenscraper.fr/medias/1/2/box3d.png",
               "miximage_path":"assets/romm/resources/roms/snes/1/mix.png",
               "title_screen_path":"assets/romm/resources/roms/snes/1/title.png",
               "marquee_path":"assets/romm/resources/roms/snes/1/marquee.png",
               "manual_url":"https://api.screenscraper.fr/api2/mediaJeu.php?media=manuel(us)"}}
        """.trimIndent()
        val game = rommJson.decodeFromString(SimpleRomDto.serializer(), json).toDomain()

        assertEquals("assets/romm/resources/roms/snes/1/cover/big.png", game.coverPath)
        assertEquals("assets/romm/resources/roms/snes/1/screenshots/0.png", game.screenshotPath)
        assertEquals("assets/romm/resources/roms/snes/1/manual/1.pdf", game.manualPath)
        assertTrue(game.hasManual)
        assertEquals("assets/romm/resources/roms/snes/1/box3d.png", game.ssMedia?.box3dPath)
        assertEquals("assets/romm/resources/roms/snes/1/mix.png", game.ssMedia?.mixPath)
        assertEquals("assets/romm/resources/roms/snes/1/title.png", game.ssMedia?.titleScreenPath)
        assertEquals("assets/romm/resources/roms/snes/1/marquee.png", game.ssMedia?.marqueePath)
    }

    @Test fun `no cover at all when RomM has not stored one, rather than the provider url`() {
        val json = """
            {"id":1,"platform_id":12,"fs_name":"G.sfc","name":"G",
             "url_cover":"https://images.igdb.com/igdb/cover.jpg"}
        """.trimIndent()
        val game = rommJson.decodeFromString(SimpleRomDto.serializer(), json).toDomain()
        assertNull(game.coverPath)
        assertNull(game.screenshotPath)
    }

    @Test fun `parses platform firmware list`() {
        val json = """
            {"id":12,"slug":"snes","fs_slug":"snes","rom_count":1,"name":"SNES","display_name":"SNES",
             "firmware":[{"id":1,"file_name":"snes.bin","file_size_bytes":512},
                         {"id":2,"file_name":"snes2.bin"}]}
        """.trimIndent()
        val dto = rommJson.decodeFromString(PlatformDto.serializer(), json)
        assertEquals(2, dto.firmware.size)
        assertEquals("snes.bin", dto.firmware.first().fileName)
    }

    @Test fun `platform firmware defaults to empty when absent`() {
        val json = """{"id":12,"slug":"snes","fs_slug":"snes","rom_count":1,"name":"X","display_name":"X"}"""
        val dto = rommJson.decodeFromString(PlatformDto.serializer(), json)
        assertTrue(dto.firmware.isEmpty())
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
