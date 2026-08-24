package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * scripts/curated-cores.txt decides what ships. The CI sync copies from it, so the asset directory
 * and the list drift apart silently if nobody checks: a rename upstream stops a core being copied,
 * and the platform quietly loses an option.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CuratedCatalogueTest {

    private val assets = ApplicationProvider
        .getApplicationContext<android.content.Context>().assets

    private fun repoFile(rel: String): File {
        // Unit tests run with the module dir as the working directory.
        val here = File(rel)
        return if (here.exists()) here else File("../$rel")
    }

    private fun curated(): Set<String> =
        repoFile("scripts/curated-cores.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    private fun shipped(): Set<String> =
        assets.list("core_info").orEmpty()
            .filter { it.endsWith(".info") }
            .map { it.removeSuffix(".info") }
            .toSet()

    @Test
    fun `the shipped catalogue is exactly the keep-list`() {
        val curated = curated()
        val shipped = shipped()
        assertEquals(
            "extra .info files shipped that are not curated:\n" +
                (shipped - curated).sorted().joinToString("\n") { "  $it" },
            emptySet<String>(), shipped - curated,
        )
        assertEquals(
            "curated cores with no .info file, so they will never be offered:\n" +
                (curated - shipped).sorted().joinToString("\n") { "  $it" },
            emptySet<String>(), curated - shipped,
        )
    }

    // The reason mamemess and ymir were dropped. A core with only one ABI would silently vanish on
    // half the devices, so it fails here instead.
    @Test
    fun `every curated core has a build for both ABIs`() {
        val abis = repoFile("app/src/main/assets/android_cores.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                line.substringBefore(' ') to line.substringAfter(' ').split(',').map { it.trim() }.toSet()
            }
        val bad = curated().filter { abis[it] != setOf("64", "32") }
        assertTrue(
            "these are not built for both arm64-v8a and armeabi-v7a:\n" +
                bad.sorted().joinToString("\n") { "  $it (${abis[it] ?: "no Android build"})" },
            bad.isEmpty(),
        )
    }

    // A platform whose declared core is not curated can never offer its own default.
    @Test
    fun `every platform default core is curated`() {
        val curated = curated()
        val json = JSONObject(assets.open("platforms.json").use { it.bufferedReader().readText() })
        val missing = json.keys().asSequence()
            .mapNotNull { tag ->
                json.getJSONObject(tag).optString("core", "").takeIf { it.isNotEmpty() }?.let { tag to it }
            }
            .filter { (_, core) -> core !in curated }
            .toList()
        assertTrue(
            "platform defaults missing from the keep-list:\n" +
                missing.joinToString("\n") { "  ${it.first} -> ${it.second}" },
            missing.isEmpty(),
        )
    }
}
