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

    @Test fun `a raw resource path gets the RomM resources prefix added`() {
        assertEquals("https://romm.example.com/assets/romm/resources/roms/12/1/box-3D/box3d.png",
            RommArtUrl.resolve(host, "roms/12/1/box-3D/box3d.png"))
        assertEquals("https://romm.example.com/assets/romm/resources/roms/12/1/manual/1.pdf",
            RommArtUrl.resolve("https://romm.example.com/", "roms/12/1/manual/1.pdf"))
    }

    @Test fun `a path RomM already rooted is joined as-is, not double-prefixed`() {
        assertEquals("https://romm.example.com/assets/romm/resources/roms/12/1/cover/big.png?ts=9",
            RommArtUrl.resolve(host, "/assets/romm/resources/roms/12/1/cover/big.png?ts=9"))
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

    @Test fun `every art type resolves to a RomM-hosted url`() {
        val g = game(
            coverPath = "/assets/romm/resources/roms/12/1/cover/big.png?ts=9",
            screenshotPath = "/assets/romm/resources/roms/12/1/screenshots/0.png",
            ssMedia = RommSsMedia(
                box3dPath = "roms/12/1/box-3D/box3d.png",
                mixPath = "roms/12/1/mix/mix.png",
                titleScreenPath = "roms/12/1/title/title.png",
                marqueePath = "roms/12/1/marquee/marquee.png",
            ),
        )
        val res = "$host/assets/romm/resources/roms/12/1"
        assertEquals("$res/cover/big.png?ts=9", RommArtUrl.forType(host, g, RommArtType.DEFAULT))
        assertEquals("$res/cover/big.png?ts=9", RommArtUrl.forType(host, g, RommArtType.BOX2D))
        assertEquals("$res/box-3D/box3d.png", RommArtUrl.forType(host, g, RommArtType.BOX3D))
        assertEquals("$res/mix/mix.png", RommArtUrl.forType(host, g, RommArtType.MIX))
        assertEquals("$res/title/title.png", RommArtUrl.forType(host, g, RommArtType.TITLE))
        assertEquals("$res/screenshots/0.png", RommArtUrl.forType(host, g, RommArtType.SCREENSHOT))
        assertEquals("$res/marquee/marquee.png", RommArtUrl.forType(host, g, RommArtType.MARQUEE))
        assertNull(RommArtUrl.forType(host, g, RommArtType.NONE))
    }

    @Test fun `art RomM has not stored renders nothing instead of hitting the provider`() {
        val g = game(coverPath = "/assets/romm/resources/roms/12/1/cover/big.png", ssMedia = RommSsMedia())
        assertNull(RommArtUrl.forType(host, g, RommArtType.BOX3D))
        assertNull(RommArtUrl.forType(host, g, RommArtType.MIX))
        assertNull(RommArtUrl.forType(host, g, RommArtType.TITLE))
        assertNull(RommArtUrl.forType(host, g, RommArtType.MARQUEE))
        assertNull(RommArtUrl.forType(host, g, RommArtType.SCREENSHOT))
    }
}
