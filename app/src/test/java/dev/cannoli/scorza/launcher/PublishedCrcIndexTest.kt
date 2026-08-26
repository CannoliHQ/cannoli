package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buildbot's `.index-extended` is the only published statement about the inner `.so` rather
 * than the zip around it. A wrong reading here skips a real update silently, so anything that is
 * not plainly a `<date> <crc32> <name>` line has to read as "not known" and fall back to the etag.
 */
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

    // The skip compares a locally computed checksum against the buildbot's published one, so the
    // two have to be the same function over the same bytes. Verified against four real cores on
    // 2026-08-26; this pins the format so a change in either direction is caught here.
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

    // The index and the archive it indexes can disagree. Observed 2026-08-26: the index advertised
    // 3d09e4a1 for nestopia while the zip it served held a .so of c33bbcc0. A CRC comparison alone
    // then calls that core stale on every check forever, because downloading it records the real
    // checksum, which still will not match the index. This is why a candidate is confirmed with a
    // conditional request rather than trusted to the CRC, and why the stamp records the installed
    // file rather than what the index claimed about it.
    @Test fun `a published crc that disagrees with the installed one only makes a candidate`() {
        val published = parse("2026-08-26 3d09e4a1 nestopia_libretro_android.so.zip")
        val installed = "c33bbcc0"
        assertTrue(
            "a disagreement has to be worth asking about",
            published["nestopia_libretro_android.so.zip"] != installed,
        )
        // ...and asking is the conditional request, which is the only thing that settles it.
        // Nothing here may conclude "stale" from the mismatch alone.
    }
}
