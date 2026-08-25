package dev.cannoli.scorza.launcher

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * There is nothing to pin to. The buildbot keeps dated APKs but a single unversioned `latest`
 * directory of cores, its stable releases carry no cores at all, and RetroArch's own updater points
 * at the same place. So Cannoli asks the server whether a build changed rather than deciding from a
 * download date, which only works if the conditional request is actually made and a 304 is actually
 * honoured.
 *
 * Verified against the real buildbot on 2026-08-25: cores and system archives both answer 304 with
 * an empty body, including ScummVM's 76 MB.
 */
class ConditionalFetchTest {

    private lateinit var server: MockWebServer
    private lateinit var out: File

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        out = Files.createTempFile("fetch", ".bin").toFile()
    }

    @After fun tearDown() {
        server.shutdown()
        out.delete()
    }

    private fun url() = server.url("/core.so.zip").toString()

    @Test fun `a first fetch sends no condition and keeps the server's etag`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"abc\"")
                .setHeader("Last-Modified", "Tue, 25 Aug 2026 13:02:02 GMT").setBody("payload")
        )

        val result = EmbeddedCoreDownloader.fetch(url(), out)

        assertTrue(result.modified)
        assertEquals("\"abc\"", result.etag)
        assertEquals("2026-08-25", result.built)
        assertEquals("payload", out.readText())
        assertNull("a first fetch must not send a condition", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test fun `a known etag is sent back as If-None-Match`() {
        server.enqueue(MockResponse().setResponseCode(304))

        EmbeddedCoreDownloader.fetch(url(), out, "\"abc\"")

        assertEquals("\"abc\"", server.takeRequest().getHeader("If-None-Match"))
    }

    // The point of the whole design: unchanged means nothing transfers and nothing is overwritten.
    @Test fun `a 304 writes nothing and reports unmodified`() {
        out.writeText("the build already installed")
        server.enqueue(MockResponse().setResponseCode(304))

        val result = EmbeddedCoreDownloader.fetch(url(), out, "\"abc\"")

        assertFalse(result.modified)
        assertEquals("the etag is kept so the next check still asks", "\"abc\"", result.etag)
        assertEquals("the installed file was touched", "the build already installed", out.readText())
    }

    // A stamp written before dates were recorded has no date, and its core may not change for
    // months. The 304 carries the validators, so that is where the date is recovered.
    @Test fun `a 304 still reports the build date, so an old stamp can be backfilled`() {
        server.enqueue(
            MockResponse().setResponseCode(304).setHeader("Last-Modified", "Tue, 25 Aug 2026 13:01:48 GMT")
        )

        val result = EmbeddedCoreDownloader.fetch(url(), out, "\"abc\"")

        assertFalse(result.modified)
        assertEquals("2026-08-25", result.built)
    }

    @Test fun `a 304 with no date reports none rather than inventing one`() {
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals("", EmbeddedCoreDownloader.fetch(url(), out, "\"abc\"").built)
    }

    @Test fun `a changed build replaces the file and returns the new etag`() {
        out.writeText("old")
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"def\"").setBody("new"))

        val result = EmbeddedCoreDownloader.fetch(url(), out, "\"abc\"")

        assertTrue(result.modified)
        assertEquals("\"def\"", result.etag)
        assertEquals("new", out.readText())
    }

    // A server that sends no validator must not look like "unchanged" on the next pass.
    @Test fun `a response with no etag reports none rather than reusing the old one`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("payload"))

        val result = EmbeddedCoreDownloader.fetch(url(), out, "\"abc\"")

        assertTrue(result.modified)
        assertNull(result.etag)
    }

    @Test fun `a real failure still throws rather than reading as unchanged`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = runCatching { EmbeddedCoreDownloader.fetch(url(), out, "\"abc\"") }.exceptionOrNull()

        assertTrue("a 500 must not be mistaken for up to date", error is RuntimeException)
    }
}
