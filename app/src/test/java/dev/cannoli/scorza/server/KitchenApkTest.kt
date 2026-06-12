package dev.cannoli.scorza.server

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KitchenApkTest {

    private class FakeInstalls(override val stagingDir: File) : ApkInstalls {
        val begun = mutableListOf<File>()
        val statuses = mutableMapOf<String, InstallStatus>()
        override fun begin(apk: File): String {
            begun.add(apk)
            return "42"
        }
        override fun status(installId: String): InstallStatus? = statuses[installId]
    }

    private class ThrowingInstalls(override val stagingDir: File) : ApkInstalls {
        override fun begin(apk: File): String = throw RuntimeException("session open failed")
        override fun status(installId: String): InstallStatus? = null
    }

    private lateinit var root: File
    private lateinit var fake: FakeInstalls
    private var server: KitchenHttpServer? = null
    private val port = 17193

    @Before fun setUp() {
        root = File.createTempFile("cannoli", "").also { it.delete(); it.mkdirs() }
        fake = FakeInstalls(File(root, "staging").also { it.mkdirs() })
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        val s = KitchenHttpServer(root, assets, port = port, pin = "TESTPIN", apkInstalls = fake)
        s.startServer()
        waitUntilReady()
        server = s
    }

    @After fun tearDown() {
        server?.stopServer()
        root.deleteRecursively()
    }

    private fun waitUntilReady() {
        repeat(50) {
            try {
                URL("http://127.0.0.1:$port/api/auth").openConnection()
                    .also { (it as HttpURLConnection).connect(); it.disconnect() }
                return
            } catch (_: Exception) { Thread.sleep(40) }
        }
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        val token = Base64.getEncoder().encodeToString("nonna:TESTPIN".toByteArray())
        conn.setRequestProperty("Authorization", "Basic $token")
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
        if (body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.readBytes()?.decodeToString() ?: ""
        conn.disconnect()
        return code to text
    }

    private fun multipartBody(filename: String, content: ByteArray): Pair<ByteArray, String> {
        val boundary = "----cannoli"
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        return head + content + tail to "multipart/form-data; boundary=$boundary"
    }

    @Test fun postApkStagesAndBeginsInstall() {
        val (bytes, contentType) = multipartBody("tool.apk", "APKDATA".toByteArray())
        val (code, body) = request("POST", "/api/apk", body = bytes, contentType = contentType)
        assertEquals(200, code)
        assertTrue(body.contains("\"installId\":\"42\""))
        assertEquals(1, fake.begun.size)
        assertEquals("APKDATA", fake.begun[0].readText())
        assertTrue(fake.begun[0].parentFile == fake.stagingDir)
    }

    @Test fun nonApkUploadIsRejected() {
        val (bytes, contentType) = multipartBody("notes.txt", "TEXT".toByteArray())
        val (code, _) = request("POST", "/api/apk", body = bytes, contentType = contentType)
        assertEquals(400, code)
        assertEquals(0, fake.begun.size)
    }

    @Test fun nonMultipartUploadIsRejected() {
        val (code, _) = request("POST", "/api/apk", body = "raw".toByteArray(), contentType = "application/octet-stream")
        assertEquals(400, code)
    }

    @Test fun statusReturnsCurrentState() {
        fake.statuses["42"] = InstallStatus(InstallStatus.FAILURE, "user declined")
        val (code, body) = request("GET", "/api/apk/42")
        assertEquals(200, code)
        assertTrue(body.contains("failure"))
        assertTrue(body.contains("user declined"))
    }

    @Test fun unknownInstallIs404() {
        val (code, _) = request("GET", "/api/apk/99")
        assertEquals(404, code)
    }

    @Test fun apkRouteUnavailableWithoutInstaller() {
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        val bare = KitchenHttpServer(root, assets, port = 17194, pin = "TESTPIN")
        bare.startServer()
        try {
            val conn = URL("http://127.0.0.1:17194/api/apk/1").openConnection() as HttpURLConnection
            val token = Base64.getEncoder().encodeToString("nonna:TESTPIN".toByteArray())
            conn.setRequestProperty("Authorization", "Basic $token")
            assertEquals(503, conn.responseCode)
            conn.disconnect()
        } finally {
            bare.stopServer()
        }
    }

    @Test fun beginFailureReturns500() {
        val throwingStaging = File(root, "staging-throw").also { it.mkdirs() }
        val throwing = ThrowingInstalls(throwingStaging)
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        val s = KitchenHttpServer(root, assets, port = 17195, pin = "TESTPIN", apkInstalls = throwing)
        s.startServer()
        try {
            repeat(50) {
                try {
                    URL("http://127.0.0.1:17195/api/auth").openConnection()
                        .also { (it as HttpURLConnection).connect(); it.disconnect() }
                    return@repeat
                } catch (_: Exception) { Thread.sleep(40) }
            }
            val (bytes, contentType) = multipartBody("tool.apk", "APKDATA".toByteArray())
            val conn = URL("http://127.0.0.1:17195/api/apk").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            val token = Base64.getEncoder().encodeToString("nonna:TESTPIN".toByteArray())
            conn.setRequestProperty("Authorization", "Basic $token")
            conn.setRequestProperty("Content-Type", contentType)
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            assertEquals(500, conn.responseCode)
            conn.disconnect()
        } finally {
            s.stopServer()
        }
    }
}
