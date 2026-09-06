package dev.cannoli.scorza.server

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class KitchenServerTest {

    private lateinit var root: File
    private var server: KitchenHttpServer? = null
    // Ephemeral: a fixed port collides with whatever else on the machine happens to
    // take it, which fails the whole file with a socket error that reads like a bug.
    private var port = 0

    @Before fun setUp() {
        root = File.createTempFile("cannoli", "").also { it.delete(); it.mkdirs() }
        File(root, "Roms/nes").mkdirs()
        File(root, "Roms/nes/Game.nes").writeText("ROMDATA")
        startServer()
    }

    @After fun tearDown() {
        server?.stopServer()
        root.deleteRecursively()
    }

    private fun startServer() {
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        val s = KitchenHttpServer(root, assets, port = 0, pin = "TESTPIN")
        s.startServer()
        port = s.listeningPort
        waitUntilReady()
        server = s
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

    private fun pin(): String = "TESTPIN"

    private fun request(
        method: String,
        path: String,
        auth: Boolean = true,
        body: ByteArray? = null,
        contentType: String? = null,
    ): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        if (auth) {
            val token = Base64.getEncoder().encodeToString("nonna:${pin()}".toByteArray())
            conn.setRequestProperty("Authorization", "Basic $token")
        }
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

    @Test fun authRequiredWithoutCredentials() {
        val (code, _) = request("GET", "/api/info", auth = false)
        assertEquals(401, code)
    }

    @Test fun infoReturnsServerName() {
        val (code, body) = request("GET", "/api/info")
        assertEquals(200, code)
        assertTrue(body.contains("Cannoli Kitchen"))
    }

    @Test fun listsRomsDirectory() {
        val (code, body) = request("GET", "/api/roms/nes")
        assertEquals(200, code)
        assertTrue(body.contains("Game.nes"))
    }

    private fun writeMapping(name: String, body: String) {
        val dir = File(root, "Config/Input/Autoconfig/android")
        dir.mkdirs()
        File(dir, name).writeText(body)
    }

    // The wizard's cfgs, so a pad the database has never seen can be sent to whoever curates it.
    // Served verbatim: the capture keys were written when the mapping was built, because neither the
    // handheld model nor the source mask can be recovered from the file later.
    @Test fun listsAndServesControllerMappings() {
        writeMapping(
            "android_default_some_pad.cfg",
            "input_device = \"Some Pad\"\nsubmission_build_model = \"AYN Thor\"\n"
        )

        val (listCode, listBody) = request("GET", "/api/mappings")
        assertEquals(200, listCode)
        assertTrue(listBody.contains("android_default_some_pad.cfg"))

        val (fileCode, fileBody) = request("GET", "/api/mappings/android_default_some_pad.cfg")
        assertEquals(200, fileCode)
        assertTrue(fileBody.contains("submission_build_model = \"AYN Thor\""))
    }

    // A mapping is named the way a person would recognise it, not by its filename. A curated entry
    // carries a friendly display name; a wizard capture repeats the pad's reported name.
    @Test fun mappingsAreNamedForReading() {
        writeMapping(
            "retroid_nova.cfg",
            "input_device = \"Retroid Pocket Controller\"\n" +
                "input_device_display_name = \"Retroid Pocket Nova\"\n" +
                "input_vendor_id = \"8226\"\ninput_product_id = \"12289\"\n"
        )
        val (code, body) = request("GET", "/api/mappings")
        assertEquals(200, code)
        assertTrue(body.contains("\"name\":\"Retroid Pocket Nova\""))
        assertTrue(body.contains("\"file\":\"retroid_nova.cfg\""))
    }

    // The directory also holds the seeder's stamp and RetroArch's own autoconfig cache. Neither is a
    // mapping, and both showed up when this reused the generic file lister.
    @Test fun mappingsListExcludesEverythingThatIsNotAMapping() {
        writeMapping("real.cfg", "input_device = \"Real Pad\"\n")
        writeMapping(".seed_version", "5b4fddaa8e967470|Retroid Pocket Nova")
        writeMapping(".autoconfig_index", "f0 = \"real.cfg\"\n")
        writeMapping("notes.txt", "not a mapping")
        writeMapping("nameless.cfg", "input_vendor_id = \"1\"\n")

        val (code, body) = request("GET", "/api/mappings")
        assertEquals(200, code)
        assertTrue(body.contains("real.cfg"))
        assertFalse(body.contains("seed_version"))
        assertFalse(body.contains("autoconfig_index"))
        assertFalse(body.contains("notes.txt"))
        assertFalse(body.contains("nameless.cfg"))
    }

    // Found on device: excluding a file from the listing is not enough if it is still fetchable by
    // name, because then the two halves disagree about what a mapping is.
    @Test fun whatIsNotListedCannotBeDownloaded() {
        writeMapping("real.cfg", "input_device = \"Real Pad\"\n")
        writeMapping(".seed_version", "5b4fddaa8e967470|Retroid Pocket Nova")
        writeMapping("notes.txt", "not a mapping")

        assertEquals(200, request("GET", "/api/mappings/real.cfg").first)
        assertEquals(404, request("GET", "/api/mappings/.seed_version").first)
        assertEquals(404, request("GET", "/api/mappings/notes.txt").first)
    }

    @Test fun unknownApiRouteIs404() {
        val (code, _) = request("GET", "/api/nonsense")
        assertEquals(404, code)
    }

    @Test fun plusInFilenameIsServed() {
        File(root, "Roms/nes/Mario + Luigi.nes").writeText("PLUS")
        val (code, body) = request("GET", "/api/roms/nes/Mario%20%2B%20Luigi.nes")
        assertEquals(200, code)
        assertEquals("PLUS", body)
    }

    @Test fun percentInFilenameIsServed() {
        File(root, "Roms/nes/100%.nes").writeText("PCT")
        val (code, body) = request("GET", "/api/roms/nes/100%25.nes")
        assertEquals(200, code)
        assertEquals("PCT", body)
    }

    @Test fun multipartUploadRoundTrip() {
        val boundary = "----cannolitest"
        val payload = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"Upload.nes\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
            append("UPLOADBYTES")
            append("\r\n--$boundary--\r\n")
        }.toByteArray()
        val (code, body) = request(
            "POST", "/api/roms/nes",
            body = payload,
            contentType = "multipart/form-data; boundary=$boundary",
        )
        assertEquals(200, code)
        assertTrue(body.contains("Upload.nes"))
        assertEquals("UPLOADBYTES", File(root, "Roms/nes/Upload.nes").readText())
    }
}
