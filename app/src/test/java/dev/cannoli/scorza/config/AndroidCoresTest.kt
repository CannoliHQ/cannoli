package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * android_cores.txt is generated from the libretro buildbot by scripts/android-cores.py and decides
 * which cores the emulator picker offers. It cannot be regenerated in a test, since that would make
 * the suite depend on the network, so what is checked here is that the committed file is coherent
 * with the rest of the app rather than that it is current.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidCoresTest {

    private val assets = ApplicationProvider
        .getApplicationContext<android.content.Context>().assets

    private fun entries(): Map<String, Set<String>> =
        assets.open("android_cores.txt").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                line.substringBefore(' ') to
                    line.substringAfter(' ').split(',').map { it.trim() }.toSet()
            }

    @Test
    fun `the asset parses and is not empty`() {
        val all = entries()
        assertTrue("expected a few hundred cores, got ${all.size}", all.size > 150)
        assertTrue(all.values.all { it.isNotEmpty() })
        assertTrue("every entry declares only ABIs we ship", all.values.flatten().all { it == "64" || it == "32" })
    }

    // The picker filters candidates through this list. A default core missing from it would leave
    // the platform with no offer at all, which is worse than the dead options the list removes.
    @Test
    fun `every platform default core has an Android build`() {
        val all = entries()
        val json = JSONObject(assets.open("platforms.json").use { it.bufferedReader().readText() })
        val missing = json.keys().asSequence()
            .mapNotNull { tag -> json.getJSONObject(tag).optString("core", "").takeIf { it.isNotEmpty() }?.let { tag to it } }
            .filter { (_, core) -> core !in all }
            .toList()
        assertTrue(
            "these platform defaults are not in android_cores.txt:\n" +
                missing.joinToString("\n") { "  ${it.first} -> ${it.second}" },
            missing.isEmpty(),
        )
    }

    // The reason the list records ABIs per core rather than being a flat set.
    @Test
    fun `the list distinguishes arm64 only cores`() {
        val all = entries()
        val arm64Only = all.filterValues { it == setOf("64") }
        assertTrue("expected some arm64-only cores", arm64Only.isNotEmpty())
        assertTrue(all.values.any { it.containsAll(listOf("64", "32")) })
    }

    // What the picker asks. A core that is genuinely absent from the buildbot must not pass, or the
    // filter is decorative.
    @Test
    fun `a core with no Android build does not run on this device`() {
        val repo = CoreInfoRepository(assets)
        assertFalse(repo.runsOnThisDevice("mupen64plus_next_libretro"))
        assertFalse(repo.runsOnThisDevice("not_a_real_core_libretro"))
    }

    @Test
    fun `a core with an Android build runs on this device`() {
        val repo = CoreInfoRepository(assets)
        assertTrue(repo.runsOnThisDevice("mupen64plus_next_gles3_libretro"))
        assertTrue(repo.runsOnThisDevice("snes9x_libretro"))
    }
}
