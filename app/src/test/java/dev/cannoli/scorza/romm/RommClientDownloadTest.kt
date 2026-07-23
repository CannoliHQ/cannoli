package dev.cannoli.scorza.romm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommClientDownloadTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val okhttp = OkHttpClient()
    private fun client() = RommClient({ server.url("/").toString().trimEnd('/') }, { okhttp })

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `downloadRom streams bytes to dest and reports progress`() {
        val payload = ByteArray(5000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val dest = File(tmp.newFolder(), "out.sfc")
        var lastTotal = -1L
        var lastDownloaded = 0L
        client().downloadRom(7, "Game (USA).sfc", dest, isCancelled = { false }) { downloaded, total ->
            lastDownloaded = downloaded; lastTotal = total
        }
        assertTrue(dest.readBytes().contentEquals(payload))
        assertEquals(5000L, lastDownloaded)
        assertEquals(5000L, lastTotal)
        val path = server.takeRequest().path!!
        assertTrue(path, path.startsWith("/api/roms/7/content/"))
        assertTrue(path, path.contains("Game") && path.contains(".sfc"))
    }

    @Test fun `downloadRom aborts when cancelled and removes the partial file`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(100000))))
        val dest = File(tmp.newFolder(), "out.sfc")
        assertThrows(RommDownloadCancelled::class.java) {
            client().downloadRom(7, "x.sfc", dest, isCancelled = { true }) { _, _ -> }
        }
        assertTrue(!dest.exists())
    }

    @Test fun `downloadRom throws RommException on http error`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val dest = File(tmp.newFolder(), "out.sfc")
        assertThrows(RommException::class.java) {
            client().downloadRom(7, "x.sfc", dest, isCancelled = { false }) { _, _ -> }
        }
    }
}
