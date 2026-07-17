package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommDtosTest {

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
        // Wire shapes as RomM actually sends them: path_cover_large and merged_screenshots arrive
        // already rooted at /assets/..., while path_manual and the ss_metadata *_path fields are
        // relative to the resources base (roms/...).
        val json = """
            {"id":1,"platform_id":12,"fs_name":"G.sfc","name":"G",
             "path_cover_large":"/assets/romm/resources/roms/12/1/cover/big.png?ts=9",
             "url_cover":"https://images.igdb.com/igdb/cover.jpg",
             "merged_screenshots":["/assets/romm/resources/roms/12/1/screenshots/0.png"],
             "has_manual":true,
             "path_manual":"roms/12/1/manual/1.pdf",
             "url_manual":"https://api.screenscraper.fr/api2/mediaJeu.php?media=manuel(us)",
             "ss_metadata":{
               "box3d_path":"roms/12/1/box-3D/box3d.png",
               "box3d_url":"https://www.screenscraper.fr/medias/1/2/box3d.png",
               "miximage_path":"roms/12/1/miximage/mix.png",
               "title_screen_path":"roms/12/1/title/title.png",
               "marquee_path":"roms/12/1/marquee/marquee.png",
               "manual_url":"https://api.screenscraper.fr/api2/mediaJeu.php?media=manuel(us)"}}
        """.trimIndent()
        val game = rommJson.decodeFromString(SimpleRomDto.serializer(), json).toDomain()

        assertEquals("/assets/romm/resources/roms/12/1/cover/big.png?ts=9", game.coverPath)
        assertEquals("/assets/romm/resources/roms/12/1/screenshots/0.png", game.screenshotPath)
        assertEquals("roms/12/1/manual/1.pdf", game.manualPath)
        assertTrue(game.hasManual)
        assertEquals("roms/12/1/box-3D/box3d.png", game.ssMedia?.box3dPath)
        assertEquals("roms/12/1/miximage/mix.png", game.ssMedia?.mixPath)
        assertEquals("roms/12/1/title/title.png", game.ssMedia?.titleScreenPath)
        assertEquals("roms/12/1/marquee/marquee.png", game.ssMedia?.marqueePath)

        // And they resolve to real RomM urls under the resources mount.
        val host = "https://romm.local"
        assertEquals("$host/assets/romm/resources/roms/12/1/box-3D/box3d.png",
            RommArtUrl.forType(host, game, RommArtType.BOX3D))
        assertEquals("$host/assets/romm/resources/roms/12/1/cover/big.png?ts=9",
            RommArtUrl.forType(host, game, RommArtType.DEFAULT))
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

    @Test fun `parses SCAN_MEDIA from the config endpoint and ignores the rest`() {
        val json = """
            {"EJS_DEBUG":false,"SCAN_METADATA_PRIORITY":["ss","igdb"],
             "SCAN_MEDIA":["box2d","box3d","screenshot","miximage","marquee","title_screen"],
             "GAMELIST_MEDIA_THUMBNAIL":"box2d"}
        """.trimIndent()
        val dto = rommJson.decodeFromString(RommConfigDto.serializer(), json)
        assertEquals(
            listOf("box2d", "box3d", "screenshot", "miximage", "marquee", "title_screen"),
            dto.scanMedia,
        )
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
