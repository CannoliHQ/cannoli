package dev.cannoli.scorza.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class KitchenUploadTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File.createTempFile("upload", "").also { it.delete(); it.mkdirs() }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun multipart(boundary: String, vararg files: Pair<String, String>): ByteArray =
        buildString {
            for ((name, content) in files) {
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n")
                append("Content-Type: application/octet-stream\r\n\r\n")
                append(content)
                append("\r\n")
            }
            append("--$boundary--\r\n")
        }.toByteArray()

    @Test fun streamsSinglePartToDestination() {
        val boundary = "xBOUNDARYx"
        val body = multipart(boundary, "rom.nes" to "HELLO-ROM")
        val saved = KitchenUpload.streamTo(
            input = ByteArrayInputStream(body),
            contentType = "multipart/form-data; boundary=$boundary",
            contentLength = body.size.toLong(),
        ) { filename -> File(dir, filename) }

        assertEquals(listOf("rom.nes"), saved)
        assertEquals("HELLO-ROM", File(dir, "rom.nes").readText())
    }

    @Test fun streamsMultipleParts() {
        val boundary = "xBOUNDARYx"
        val body = multipart(boundary, "a.nes" to "AAA", "b.nes" to "BBBB")
        val saved = KitchenUpload.streamTo(
            input = ByteArrayInputStream(body),
            contentType = "multipart/form-data; boundary=$boundary",
            contentLength = body.size.toLong(),
        ) { filename -> File(dir, filename) }

        assertEquals(listOf("a.nes", "b.nes"), saved)
        assertEquals("AAA", File(dir, "a.nes").readText())
        assertEquals("BBBB", File(dir, "b.nes").readText())
    }

    @Test fun skipsPathTraversalFilename() {
        val boundary = "xBOUNDARYx"
        val body = multipart(boundary, ".." to "DANGER")
        val saved = KitchenUpload.streamTo(
            input = ByteArrayInputStream(body),
            contentType = "multipart/form-data; boundary=$boundary",
            contentLength = body.size.toLong(),
        ) { filename -> File(dir, filename) }

        assertEquals(emptyList<String>(), saved)
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
