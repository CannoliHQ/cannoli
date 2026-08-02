package dev.cannoli.scorza.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.ArtworkLookup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class KitchenAppsSettingsTest {

    private var server: KitchenHttpServer? = null
    private var root: File? = null
    private val port = 17196

    @After fun tearDown() {
        server?.stopServer()
        root?.deleteRecursively()
    }

    private fun newRoot(): File =
        File.createTempFile("cannoli", "").also { it.delete(); it.mkdirs() }.also { root = it }

    private fun pathsFor(dir: File): CannoliPathsProvider {
        File(dir, "Config").mkdirs()
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = dir.absolutePath
        return CannoliPathsProvider(settings)
    }

    private fun appsRepo(dir: File): AppsRepository {
        val paths = pathsFor(dir)
        return AppsRepository(CannoliDatabase(paths), ArtworkLookup(paths))
    }

    private fun start(
        dir: File,
        apps: AppsRepository? = null,
        settingsProvider: (() -> SettingsResponse)? = null,
    ) {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val s = if (settingsProvider != null) {
            KitchenHttpServer(dir, assets, port = port, pin = PIN, appsRepository = apps, settingsProvider = settingsProvider)
        } else {
            KitchenHttpServer(dir, assets, port = port, pin = PIN, appsRepository = apps)
        }
        s.startServer()
        repeat(50) {
            try {
                URL("http://127.0.0.1:$port/api/auth").openConnection()
                    .also { c -> (c as HttpURLConnection).connect(); c.disconnect() }
                return@repeat
            } catch (_: Exception) { Thread.sleep(40) }
        }
        server = s
    }

    private fun get(path: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        val token = Base64.getEncoder().encodeToString("nonna:$PIN".toByteArray())
        conn.setRequestProperty("Authorization", "Basic $token")
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.readBytes()?.decodeToString() ?: ""
        conn.disconnect()
        return code to text
    }

    @Test fun `apps returns tool and port display names`() {
        val dir = newRoot()
        val repo = appsRepo(dir)
        repo.upsert(AppType.TOOL, "Termux", "com.termux")
        repo.upsert(AppType.PORT, "Portal 2", "com.valve.portal2")
        start(dir, apps = repo)

        val (code, body) = get("/api/apps")
        assertEquals(200, code)
        assertTrue(body.contains("Termux"))
        assertTrue(body.contains("Portal 2"))
    }

    @Test fun `apps returns empty lists when there are none`() {
        val dir = newRoot()
        start(dir, apps = appsRepo(dir))

        val (code, body) = get("/api/apps")
        assertEquals(200, code)
        assertEquals("""{"tools":[],"ports":[]}""", body)
    }

    @Test fun `apps is unavailable when the repository is not wired`() {
        val dir = newRoot()
        start(dir)

        val (code, _) = get("/api/apps")
        assertEquals(503, code)
    }

    @Test fun `apps does not leak package names`() {
        val dir = newRoot()
        val repo = appsRepo(dir)
        repo.upsert(AppType.TOOL, "Termux", "com.termux")
        repo.upsert(AppType.PORT, "Portal 2", "com.valve.portal2")
        start(dir, apps = repo)

        val (_, body) = get("/api/apps")
        assertFalse(body.contains("com.termux"))
        assertFalse(body.contains("com.valve.portal2"))
    }

    @Test fun `apps returns sanitized art basenames`() {
        val dir = newRoot()
        val repo = appsRepo(dir)
        repo.upsert(AppType.TOOL, "Moonlight: Game Streaming", "com.limelight")
        start(dir, apps = repo)

        val (code, body) = get("/api/apps")
        assertEquals(200, code)
        assertTrue(body.contains("Moonlight - Game Streaming"))
    }

    @Test fun `apps does not build the art cache the launcher reads`() {
        val dir = newRoot()
        val paths = pathsFor(dir)
        val db = CannoliDatabase(paths)
        AppsRepository(db, ArtworkLookup(paths)).upsert(AppType.TOOL, "Termux", "com.termux")

        val repo = AppsRepository(db, ArtworkLookup(paths))
        start(dir, apps = repo)
        assertEquals(200, get("/api/apps").first)

        File(dir, "Art/TOOLS").mkdirs()
        File(dir, "Art/TOOLS/Termux.png").writeBytes(ByteArray(4))

        assertEquals("Termux.png", repo.all(AppType.TOOL).single().artFile?.name)
    }

    @Test fun `settings returns the configured names`() {
        val dir = newRoot()
        start(dir, settingsProvider = { SettingsResponse("Homebrew", "Games") })

        val (code, body) = get("/api/settings")
        assertEquals(200, code)
        assertEquals("""{"toolsName":"Homebrew","portsName":"Games"}""", body)
    }

    @Test fun `settings falls back to the defaults when unwired`() {
        val dir = newRoot()
        start(dir)

        val (code, body) = get("/api/settings")
        assertEquals(200, code)
        assertEquals("""{"toolsName":"Tools","portsName":"Ports"}""", body)
    }

    @Test fun `settings exposes nothing beyond the two names`() {
        val dir = newRoot()
        start(dir, settingsProvider = { SettingsResponse("Tools", "Ports") })

        val (_, body) = get("/api/settings")
        val keys = Regex("\"(\\w+)\":").findAll(body).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("toolsName", "portsName"), keys)
    }

    private companion object {
        const val PIN = "TESTPIN"
    }
}
