package dev.cannoli.scorza.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.Rom
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** A ROM directory the launcher did not scaffold keeps whatever folder names the user already had,
 *  and the launcher resolves those case-insensitively. The dashboard drives every request off the
 *  tag list, so a tag it is handed has to be one the games endpoint will serve. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KitchenPlatformTagTest {

    private var server: KitchenHttpServer? = null
    private var root: File? = null
    // Ephemeral: a fixed port collides with whatever else on the machine happens to
    // take it, which fails the whole file with a socket error that reads like a bug.
    private var port = 0

    @After fun tearDown() {
        server?.stopServer()
        root?.deleteRecursively()
    }

    private fun newRoot(): File =
        File.createTempFile("cannoli", "").also { it.delete(); it.mkdirs() }.also { root = it }

    private fun repoWith(vararg knownTags: String): RomsRepository {
        val repo = mockk<RomsRepository>()
        every { repo.knownPlatformTags() } returns knownTags.toList()
        every { repo.allRomsForPlatform(any()) } answers {
            val tag = firstArg<String>().uppercase()
            if (tag in knownTags.map { it.uppercase() }) listOf(rom(tag)) else emptyList()
        }
        return repo
    }

    private fun rom(tag: String) = Rom(
        id = 1L,
        path = File(root, "Roms/$tag/Tetris.gb"),
        platformTag = tag,
        displayName = "Tetris",
        tags = null,
        artFile = null,
        raGameId = null,
    )

    private fun start(dir: File, repo: RomsRepository?, configuredTags: Collection<String> = emptyList()) {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val s = KitchenHttpServer(
            dir, assets, port = 0, pin = PIN,
            romsRepository = repo,
            platformTagsProvider = { configuredTags },
        )
        s.startServer()
        port = s.listeningPort
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

    private fun advertisedTags(): List<String> {
        val (code, body) = get("/api/tags")
        assertEquals(200, code)
        val array = JSONObject(body).getJSONArray("tags")
        return (0 until array.length()).map { array.getString(it) }
    }

    /** The dashboard reaches the games endpoint through encodeURIComponent, so a tag carrying a
     *  space or a fragment marker still arrives whole. */
    private fun gamesStatus(tag: String): Int =
        get("/api/games/" + java.net.URLEncoder.encode(tag, "UTF-8").replace("+", "%20")).first

    @Test fun `games are served for a lowercase platform folder`() {
        val dir = newRoot()
        File(dir, "Roms/gb").mkdirs()
        File(dir, "Roms/gb/Tetris.gb").writeText("ROM")
        start(dir, repoWith("GB"))

        val tag = advertisedTags().single()
        assertEquals(200, gamesStatus(tag))
    }

    @Test fun `every advertised tag resolves to a games listing`() {
        val dir = newRoot()
        listOf("GB", "snes", "Nintendo 64", "#recycle").forEach { File(dir, "Roms/$it").mkdirs() }
        start(dir, repoWith("GB", "SNES"))

        val stranded = advertisedTags().filter { gamesStatus(it) != 200 }
        assertEquals(emptyList<String>(), stranded)
    }

    @Test fun `a platform with no database row yet is still listed and served`() {
        val dir = newRoot()
        File(dir, "Roms/NGPC").mkdirs()
        start(dir, repoWith(), configuredTags = listOf("NGPC"))

        assertEquals(listOf("NGPC"), advertisedTags())
        assertEquals(200, gamesStatus("NGPC"))
    }

    @Test fun `tools and ports are left to the apps endpoint`() {
        val dir = newRoot()
        listOf("GB", "TOOLS", "PORTS").forEach { File(dir, "Roms/$it").mkdirs() }
        start(dir, repoWith("GB", "TOOLS", "PORTS"))

        assertEquals(listOf("GB"), advertisedTags())
    }

    @Test fun `tags stay renderable when no repository is wired`() {
        val dir = newRoot()
        File(dir, "Roms/GB").mkdirs()
        start(dir, repo = null, configuredTags = listOf("GB"))

        assertEquals(200, get("/api/tags").first)
        assertEquals(listOf("GB"), advertisedTags())
    }

    private companion object {
        const val PIN = "TESTPIN"
    }
}
