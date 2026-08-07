package dev.cannoli.scorza.input.autoconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoconfigSeederTest {

    @get:Rule val tmp = TemporaryFolder()

    private val assets = MapCfgSource(
        mapOf(
            "autoconfig/android/PadA.cfg" to "input_device = \"Pad A\"\ninput_b_btn = \"96\"\n",
            "autoconfig/android/PadB.cfg" to "input_device = \"Pad B\"\ninput_b_btn = \"96\"\n",
        )
    )

    private fun seeder(target: File, legacy: File, version: Int = 1) =
        AutoconfigSeeder(assets, target, legacy, version)

    @Test
    fun `seeds all assets and stamps the version`() {
        val target = tmp.newFolder("android")
        seeder(target, tmp.newFolder("Mappings")).seedIfNeeded()
        assertTrue(File(target, "PadA.cfg").exists())
        assertTrue(File(target, "PadB.cfg").exists())
        assertEquals("1", File(target, ".seed_version").readText().trim())
    }

    @Test
    fun `same version does not rewrite`() {
        val target = tmp.newFolder("android")
        val legacy = tmp.newFolder("Mappings")
        seeder(target, legacy).seedIfNeeded()
        File(target, "PadA.cfg").writeText("locally changed, not user flagged")
        seeder(target, legacy).seedIfNeeded()
        assertEquals("locally changed, not user flagged", File(target, "PadA.cfg").readText())
    }

    @Test
    fun `version bump reseeds but preserves user files`() {
        val target = tmp.newFolder("android")
        val legacy = tmp.newFolder("Mappings")
        seeder(target, legacy, version = 1).seedIfNeeded()
        File(target, "PadA.cfg").writeText("input_device = \"Pad A\"\ncannoli_user = \"true\"\n")
        File(target, "PadB.cfg").writeText("stale bundled content")
        seeder(target, legacy, version = 2).seedIfNeeded()
        assertTrue(File(target, "PadA.cfg").readText().contains("cannoli_user"))
        assertTrue(File(target, "PadB.cfg").readText().contains("input_b_btn"))
    }

    @Test
    fun `unreadable stamp does not crash the seed`() {
        val target = tmp.newFolder("android")
        File(target, ".seed_version").mkdirs()
        seeder(target, tmp.newFolder("Mappings")).seedIfNeeded()
    }

    @Test
    fun `deletes the legacy ini store`() {
        val target = tmp.newFolder("android")
        val legacy = tmp.newFolder("Mappings")
        File(legacy, "old.ini").writeText("[meta]\ndisplay_name=Old\n")
        seeder(target, legacy).seedIfNeeded()
        assertFalse(legacy.exists())
    }
}