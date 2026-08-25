package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What the server said when we installed something. Losing it is not a failure, it just means the
 * next check is unconditional and downloads a build we may already have, so every read is written
 * to fail soft rather than throw.
 */
class DownloadStampsTest {

    private fun dir(): File = Files.createTempDirectory("stamps").toFile()

    @Test fun `a stamp survives a round trip`() {
        val d = dir()
        DownloadStamps.put(d, "https://host/core.so.zip", "\"abc\"", "2026-08-25")
        assertEquals("\"abc\"", DownloadStamps.etagFor(d, "https://host/core.so.zip"))
    }

    @Test fun `a second stamp does not displace the first`() {
        val d = dir()
        DownloadStamps.put(d, "https://host/a.zip", "\"1\"", "2026-08-25")
        DownloadStamps.put(d, "https://host/b.zip", "\"2\"", "2026-08-25")
        assertEquals("\"1\"", DownloadStamps.etagFor(d, "https://host/a.zip"))
        assertEquals("\"2\"", DownloadStamps.etagFor(d, "https://host/b.zip"))
    }

    @Test fun `re-recording a url replaces its stamp`() {
        val d = dir()
        DownloadStamps.put(d, "https://host/a.zip", "\"old\"", "2026-08-25")
        DownloadStamps.put(d, "https://host/a.zip", "\"new\"", "2026-08-25")
        assertEquals("\"new\"", DownloadStamps.etagFor(d, "https://host/a.zip"))
        assertEquals(1, DownloadStamps.read(d).size)
    }

    // A server that sends no validator must record nothing, so the next check stays unconditional
    // rather than sending a blank condition the server could match on.
    @Test fun `a missing etag is not recorded`() {
        val d = dir()
        DownloadStamps.put(d, "https://host/a.zip", null, "2026-08-25")
        DownloadStamps.put(d, "https://host/b.zip", "", "2026-08-25")
        assertTrue(DownloadStamps.read(d).isEmpty())
    }

    // The date is what the screen shows. A server that sent no Last-Modified must read as absent
    // rather than as an empty string rendered into the row.
    @Test fun `a build date is kept beside the etag, and its absence reads as absent`() {
        val d = dir()
        DownloadStamps.put(d, "https://host/a.zip", "\"1\"", "2026-08-25")
        DownloadStamps.put(d, "https://host/b.zip", "\"2\"", "")
        assertEquals("2026-08-25", DownloadStamps.builtFor(d, "https://host/a.zip"))
        assertNull(DownloadStamps.builtFor(d, "https://host/b.zip"))
        assertEquals("\"2\"", DownloadStamps.etagFor(d, "https://host/b.zip"))
    }

    @Test fun `a stamp written before dates were recorded still reads`() {
        val d = dir()
        File(d, "download_etags.txt").writeText("https://host/a.zip\t\"old\"\n")
        assertEquals("\"old\"", DownloadStamps.etagFor(d, "https://host/a.zip"))
        assertNull(DownloadStamps.builtFor(d, "https://host/a.zip"))
    }

    @Test fun `the servers date header becomes an ISO date`() {
        assertEquals("2026-08-25", DownloadStamps.isoDate("Tue, 25 Aug 2026 13:02:02 GMT"))
        assertEquals("", DownloadStamps.isoDate("not a date"))
        assertEquals("", DownloadStamps.isoDate(null))
    }

    @Test fun `nothing recorded reads as nothing known`() {
        assertNull(DownloadStamps.etagFor(dir(), "https://host/a.zip"))
    }

    // Etags carry quotes and urls carry query strings; only the first tab separates them.
    @Test fun `a stamp with awkward characters survives`() {
        val d = dir()
        val url = "https://host/path%20with%20space.zip?x=1&y=2"
        DownloadStamps.put(d, url, "W/\"weak-123\"", "2026-08-25")
        assertEquals("W/\"weak-123\"", DownloadStamps.etagFor(d, url))
    }

    @Test fun `a corrupt line is skipped rather than losing the file`() {
        val d = dir()
        DownloadStamps.put(d, "https://host/a.zip", "\"1\"", "2026-08-25")
        File(d, "download_etags.txt").appendText("\nnot-a-pair\n\thanging-tab\n")
        assertEquals("\"1\"", DownloadStamps.etagFor(d, "https://host/a.zip"))
    }

    @Test fun `an unreadable store reads empty rather than throwing`() {
        val d = dir()
        File(d, "download_etags.txt").mkdirs()
        assertTrue(DownloadStamps.read(d).isEmpty())
    }
}
