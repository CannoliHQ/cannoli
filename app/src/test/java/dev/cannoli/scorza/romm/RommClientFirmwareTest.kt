package dev.cannoli.scorza.romm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RommClientFirmwareTest {
    private lateinit var server: MockWebServer
    private val okhttp = OkHttpClient()
    private fun client() = RommClient({ server.url("/").toString().trimEnd('/') }, { okhttp })

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `getFirmware lists firmware for a platform`() {
        server.enqueue(MockResponse().setBody(
            """[{"id":3,"file_name":"scph5501.bin","file_size_bytes":524288,"md5_hash":"abc","sha1_hash":null,"crc_hash":null}]"""
        ))
        val fw = client().getFirmware(7)
        assertEquals(1, fw.size)
        assertEquals(3, fw[0].id)
        assertEquals("scph5501.bin", fw[0].fileName)
        assertEquals(524288L, fw[0].sizeBytes)
        val req = server.takeRequest()
        assertEquals("/api/firmware?platform_id=7", req.path)
    }

    @Test fun `downloadFirmware streams to dest`() {
        val payload = ByteArray(2048) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val dest = java.io.File.createTempFile("fw_", ".bin").apply { delete() }
        client().downloadFirmware(3, "scph5501.bin", dest, isCancelled = { false }) { _, _ -> }
        org.junit.Assert.assertTrue(dest.readBytes().contentEquals(payload))
        val path = server.takeRequest().path!!
        org.junit.Assert.assertTrue(path, path.startsWith("/api/firmware/3/content/"))
        org.junit.Assert.assertTrue(path, path.contains("scph5501.bin"))
        dest.delete()
    }
}
