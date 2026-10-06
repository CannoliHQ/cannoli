package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedCrcIndexTest {

    private fun parse(vararg lines: String) =
        EmbeddedCoreDownloader.parseIndex(lines.asSequence())

    @Test fun `a well formed line yields its crc`() {
        val m = parse("2026-08-26 e861a19d pokemini_libretro_android.so.zip")
        assertEquals("e861a19d", m["pokemini_libretro_android.so.zip"])
    }

    @Test fun `crcs are lowercased so comparison is not case sensitive`() {
        val m = parse("2026-08-26 E861A19D pokemini_libretro_android.so.zip")
        assertEquals("e861a19d", m["pokemini_libretro_android.so.zip"])
    }

    @Test fun `a truncated or malformed line is skipped rather than guessed at`() {
        val m = parse(
            "2026-08-26 e861a19d pokemini_libretro_android.so.zip",
            "2026-08-26 short vecx_libretro_android.so.zip",
            "2026-08-26 notahexvalue vecx_libretro_android.so.zip",
            "2026-08-26",
            "",
        )
        assertEquals(1, m.size)
        assertTrue(m.containsKey("pokemini_libretro_android.so.zip"))
    }

    @Test fun `many entries parse in one pass`() {
        val lines = (1..200).map { "2026-08-26 %08x core$it.so.zip".format(it) }
        val m = EmbeddedCoreDownloader.parseIndex(lines.asSequence())
        assertEquals(200, m.size)
        assertEquals("00000001", m["core1.so.zip"])
    }

    @Test fun `a local checksum is lowercase hex of the same width the index publishes`() {
        val f = java.io.File.createTempFile("core", ".so")
        f.writeBytes("pokemini".toByteArray())
        val crc = EmbeddedCoreDownloader.crc32Of(f)!!
        assertEquals(8, crc.length)
        assertTrue(crc.all { it.isDigit() || it in 'a'..'f' })

        val expected = java.util.zip.CRC32().apply { update("pokemini".toByteArray()) }.value
        assertEquals("%08x".format(expected), crc)
    }

    @Test fun `a checksum of a missing file reads as unknown rather than throwing`() {
        assertEquals(null, EmbeddedCoreDownloader.crc32Of(java.io.File("/does/not/exist.so")))
    }
}
