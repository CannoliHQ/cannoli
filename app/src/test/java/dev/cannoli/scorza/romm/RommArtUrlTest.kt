package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RommArtUrlTest {

    private val host = "https://romm.example.com"

    private fun game(
        coverPath: String? = null,
        screenshotPath: String? = null,
        ssMedia: RommSsMedia? = null,
    ) = RommGame(
        id = 1,
        platformId = 2,
        name = "Chrono Trigger",
        fsName = "Chrono Trigger (USA).sfc",
        sizeBytes = 0,
        summary = null,
        revision = null,
        regions = emptyList(),
        languages = emptyList(),
        coverPath = coverPath,
        files = emptyList(),
        ssMedia = ssMedia,
        screenshotPath = screenshotPath,
    )

    @Test fun `relative path joined to host with single slash`() {
        assertEquals("https://romm.example.com/assets/roms/snes/1/cover.png",
            RommArtUrl.resolve("https://romm.example.com/", "assets/roms/snes/1/cover.png"))
        assertEquals("https://romm.example.com/assets/x.png",
            RommArtUrl.resolve(host, "/assets/x.png"))
    }

    @Test fun `an absolute url on the RomM host passes through`() {
        assertEquals("https://romm.example.com/assets/x.png",
            RommArtUrl.resolve(host, "https://romm.example.com/assets/x.png"))
    }

    @Test fun `an off-host url is refused rather than fetched`() {
        assertNull(RommArtUrl.resolve(host, "https://www.screenscraper.fr/medias/1/2/box3d.png"))
        assertNull(RommArtUrl.resolve(host, "https://images.igdb.com/igdb/image/upload/cover.jpg"))
        assertNull(RommArtUrl.resolve(host, "http://cdn.example/cover.png"))
    }

    @Test fun `null or blank cover returns null`() {
        assertNull(RommArtUrl.resolve(host, null))
        assertNull(RommArtUrl.resolve(host, "  "))
    }

    @Test fun `every art type resolves to a RomM-hosted path`() {
        val g = game(
            coverPath = "assets/cover.png",
            screenshotPath = "assets/screenshot.png",
            ssMedia = RommSsMedia(
                box3dPath = "assets/box3d.png",
                mixPath = "assets/mix.png",
                titleScreenPath = "assets/title.png",
                marqueePath = "assets/marquee.png",
            ),
        )
        assertEquals("$host/assets/cover.png", RommArtUrl.forType(host, g, RommArtType.DEFAULT))
        assertEquals("$host/assets/cover.png", RommArtUrl.forType(host, g, RommArtType.BOX2D))
        assertEquals("$host/assets/box3d.png", RommArtUrl.forType(host, g, RommArtType.BOX3D))
        assertEquals("$host/assets/mix.png", RommArtUrl.forType(host, g, RommArtType.MIX))
        assertEquals("$host/assets/title.png", RommArtUrl.forType(host, g, RommArtType.TITLE))
        assertEquals("$host/assets/screenshot.png", RommArtUrl.forType(host, g, RommArtType.SCREENSHOT))
        assertEquals("$host/assets/marquee.png", RommArtUrl.forType(host, g, RommArtType.MARQUEE))
        assertNull(RommArtUrl.forType(host, g, RommArtType.NONE))
    }

    @Test fun `art RomM has not stored renders nothing instead of hitting the provider`() {
        val g = game(coverPath = "assets/cover.png", ssMedia = RommSsMedia())
        assertNull(RommArtUrl.forType(host, g, RommArtType.BOX3D))
        assertNull(RommArtUrl.forType(host, g, RommArtType.MIX))
        assertNull(RommArtUrl.forType(host, g, RommArtType.TITLE))
        assertNull(RommArtUrl.forType(host, g, RommArtType.MARQUEE))
        assertNull(RommArtUrl.forType(host, g, RommArtType.SCREENSHOT))
    }
}
