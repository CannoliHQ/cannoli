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

    private fun seeder(target: File, legacy: File, digest: String = "d", buildModel: String = "TestModel") =
        AutoconfigSeeder(assets, { target }, { legacy }, digest, buildModel)

    private fun fakeSource(vararg files: Pair<String, String>) = object : CfgSource {
        override fun listCfgFiles(): List<String> = files.map { it.first }
        override fun open(name: String): java.io.InputStream =
            files.first { it.first == name }.second.byteInputStream()
    }

    private fun seeder(source: CfgSource, buildModel: String) = AutoconfigSeeder(
        source = source,
        targetDirProvider = { tmp.root },
        legacyMappingsDirProvider = { java.io.File(tmp.root, "legacy") },
        assetsDigest = "testdigest",
        buildModel = buildModel,
    )

    @Test
    fun `seeds all assets and stamps the digest`() {
        val target = tmp.newFolder("android")
        seeder(target, tmp.newFolder("Mappings")).seedIfNeeded()
        assertTrue(File(target, "PadA.cfg").exists())
        assertTrue(File(target, "PadB.cfg").exists())
        assertEquals("d|TestModel", File(target, ".seed_version").readText().trim())
    }

    @Test
    fun `same digest does not rewrite`() {
        val target = tmp.newFolder("android")
        val legacy = tmp.newFolder("Mappings")
        seeder(target, legacy).seedIfNeeded()
        File(target, "PadA.cfg").writeText("locally changed, not user flagged")
        seeder(target, legacy).seedIfNeeded()
        assertEquals("locally changed, not user flagged", File(target, "PadA.cfg").readText())
    }

    @Test
    fun `digest change reseeds but preserves user files`() {
        val target = tmp.newFolder("android")
        val legacy = tmp.newFolder("Mappings")
        seeder(target, legacy, digest = "d1").seedIfNeeded()
        File(target, "PadA.cfg").writeText("input_device = \"Pad A\"\ncannoli_user = \"true\"\n")
        File(target, "PadB.cfg").writeText("stale bundled content")
        seeder(target, legacy, digest = "d2").seedIfNeeded()
        assertTrue(File(target, "PadA.cfg").readText().contains("cannoli_user"))
        assertTrue(File(target, "PadB.cfg").readText().contains("input_b_btn"))
    }

    // seedIfNeeded no longer swallows I/O failures; the caller is given somewhere to report them
    // in a later task.
    @Test(expected = java.io.IOException::class)
    fun `unreadable stamp propagates the failure`() {
        val target = tmp.newFolder("android")
        File(target, ".seed_version").mkdirs()
        seeder(target, tmp.newFolder("Mappings")).seedIfNeeded()
    }

    // The SD root can change under a running app, so the seeder has to read the directory the way
    // the repository does: when it seeds, not when it was built.
    @Test
    fun `the target directory is resolved at seed time`() {
        var target = tmp.newFolder("first")
        val legacy = tmp.newFolder("Mappings")
        val seeder = AutoconfigSeeder(assets, { target }, { legacy }, "d", "TestModel")
        target = tmp.newFolder("second")

        seeder.seedIfNeeded()

        assertTrue(File(target, "PadA.cfg").exists())
        assertFalse(File(tmp.root, "first/PadA.cfg").exists())
    }

    @Test
    fun `reseeding one file restores the bundled content over a user edit`() {
        val target = tmp.newFolder("android")
        val seeder = seeder(target, tmp.newFolder("Mappings"))
        seeder.seedIfNeeded()
        File(target, "PadA.cfg").writeText("input_device = \"Pad A\"\ncannoli_user = \"true\"\n")

        assertTrue(seeder.reseedSingle("PadA.cfg"))

        assertEquals("input_device = \"Pad A\"\ninput_b_btn = \"96\"\n", File(target, "PadA.cfg").readText())
    }

    @Test
    fun `reseeding a file with no bundled counterpart writes nothing`() {
        val target = tmp.newFolder("android")

        assertFalse(seeder(target, tmp.newFolder("Mappings")).reseedSingle("PadZ.cfg"))

        assertFalse(File(target, "PadZ.cfg").exists())
    }

    @Test
    fun `deletes the legacy ini store`() {
        val target = tmp.newFolder("android")
        val legacy = tmp.newFolder("Mappings")
        File(legacy, "old.ini").writeText("[meta]\ndisplay_name=Old\n")
        seeder(target, legacy).seedIfNeeded()
        assertFalse(legacy.exists())
    }

    @Test fun `pinned cfgs seed only onto their own model`() {
        val source = fakeSource(
            "retroid_nova.cfg" to "input_device = \"Retroid Pocket Controller\"\ncannoli_build_model = \"Retroid Pocket Nova\"\n",
            "ayn_thor.cfg" to "input_device = \"Odin Controller\"\ncannoli_build_model = \"AYN Thor\"\n",
            "sony_ds4.cfg" to "input_device = \"Wireless Controller\"\n",
        )
        seeder(source, buildModel = "Retroid Pocket Nova").seedIfNeeded()
        assertEquals(
            setOf(".seed_version", "retroid_nova.cfg", "sony_ds4.cfg"),
            tmp.root.list()!!.toSet()
        )
    }

    @Test fun `pruning removes stale input db files but never user files`() {
        java.io.File(tmp.root, "ayn_thor.cfg").writeText("input_device = \"Odin Controller\"\ncannoli_source = \"INPUT_DB\"\n")
        java.io.File(tmp.root, "mine.cfg").writeText("input_device = \"My Pad\"\ncannoli_source = \"USER\"\n")
        seeder(fakeSource("sony_ds4.cfg" to "input_device = \"Wireless Controller\"\n"), buildModel = "RG Rotate").seedIfNeeded()
        assertEquals(false, java.io.File(tmp.root, "ayn_thor.cfg").exists())
        assertEquals(true, java.io.File(tmp.root, "mine.cfg").exists())
    }

    @Test fun `a user file is never overwritten by its curated original`() {
        java.io.File(tmp.root, "sony_ds4.cfg").writeText("input_device = \"Wireless Controller\"\ncannoli_source = \"USER\"\ninput_b_btn = \"97\"\n")
        seeder(fakeSource("sony_ds4.cfg" to "input_device = \"Wireless Controller\"\ninput_b_btn = \"96\"\n"), buildModel = "RG Rotate").seedIfNeeded()
        assertEquals(true, java.io.File(tmp.root, "sony_ds4.cfg").readText().contains("97"))
    }

    @Test fun `a changed digest re-seeds and an unchanged one does not`() {
        val source = fakeSource("sony_ds4.cfg" to "input_device = \"Wireless Controller\"\ninput_b_btn = \"96\"\n")
        AutoconfigSeeder(source, { tmp.root }, { java.io.File(tmp.root, "legacy") }, "digest-a", "RG Rotate").seedIfNeeded()
        java.io.File(tmp.root, "sony_ds4.cfg").writeText("tampered")

        AutoconfigSeeder(source, { tmp.root }, { java.io.File(tmp.root, "legacy") }, "digest-a", "RG Rotate").seedIfNeeded()
        assertEquals("tampered", java.io.File(tmp.root, "sony_ds4.cfg").readText())

        AutoconfigSeeder(source, { tmp.root }, { java.io.File(tmp.root, "legacy") }, "digest-b", "RG Rotate").seedIfNeeded()
        assertEquals(true, java.io.File(tmp.root, "sony_ds4.cfg").readText().contains("input_b_btn"))
    }

    @Test fun `a model change re-seeds`() {
        val source = fakeSource(
            "retroid_nova.cfg" to "input_device = \"Retroid Pocket Controller\"\ncannoli_build_model = \"Retroid Pocket Nova\"\n",
            "retroid_classic.cfg" to "input_device = \"Retroid Pocket Controller\"\ncannoli_build_model = \"Retroid Pocket Classic\"\n",
        )
        AutoconfigSeeder(source, { tmp.root }, { java.io.File(tmp.root, "legacy") }, "d", "Retroid Pocket Nova").seedIfNeeded()
        assertEquals(true, java.io.File(tmp.root, "retroid_nova.cfg").exists())

        AutoconfigSeeder(source, { tmp.root }, { java.io.File(tmp.root, "legacy") }, "d", "Retroid Pocket Classic").seedIfNeeded()
        assertEquals(true, java.io.File(tmp.root, "retroid_classic.cfg").exists())
        assertEquals(false, java.io.File(tmp.root, "retroid_nova.cfg").exists())
    }

    @Test fun `an unreadable file survives pruning`() {
        java.io.File(tmp.root, "corrupt.cfg").mkdirs()
        seeder(fakeSource("sony_ds4.cfg" to "input_device = \"Wireless Controller\"\n"), buildModel = "RG Rotate").seedIfNeeded()
        assertEquals(true, java.io.File(tmp.root, "corrupt.cfg").exists())
    }

    @Test fun `an unkeyed foreign cfg survives pruning`() {
        java.io.File(tmp.root, "my_pad.cfg").writeText("input_device = \"My Pad\"\n")
        seeder(fakeSource("sony_ds4.cfg" to "input_device = \"Wireless Controller\"\n"), buildModel = "RG Rotate").seedIfNeeded()
        assertEquals(true, java.io.File(tmp.root, "my_pad.cfg").exists())
    }

    @Test fun `an unkeyed stale shipped cfg is pruned even when it no longer applies to this model`() {
        java.io.File(tmp.root, "ayn_thor.cfg").writeText("input_device = \"Odin Controller\"\n")
        val source = fakeSource(
            "ayn_thor.cfg" to "input_device = \"Odin Controller\"\ncannoli_build_model = \"AYN Thor\"\n",
            "sony_ds4.cfg" to "input_device = \"Wireless Controller\"\n",
        )
        seeder(source, buildModel = "RG Rotate").seedIfNeeded()
        assertEquals(false, java.io.File(tmp.root, "ayn_thor.cfg").exists())
    }

    @Test fun `a legacy user file absent from the source set survives pruning`() {
        java.io.File(tmp.root, "mine.cfg").writeText("input_device = \"My Pad\"\ncannoli_user = \"true\"\n")
        seeder(fakeSource("sony_ds4.cfg" to "input_device = \"Wireless Controller\"\n"), buildModel = "RG Rotate").seedIfNeeded()
        assertEquals(true, java.io.File(tmp.root, "mine.cfg").exists())
    }

    @Test fun `a pin matches regardless of surrounding whitespace or case`() {
        val source = fakeSource(
            "retroid_nova.cfg" to "input_device = \"Retroid Pocket Controller\"\ncannoli_build_model = \" retroid pocket nova \"\n",
        )
        seeder(source, buildModel = "Retroid Pocket Nova").seedIfNeeded()
        assertEquals(true, java.io.File(tmp.root, "retroid_nova.cfg").exists())
    }

    @Test fun `a source that throws propagates rather than failing silently`() {
        val exploding = object : CfgSource {
            override fun listCfgFiles(): List<String> = listOf("boom.cfg")
            override fun open(name: String): java.io.InputStream = throw java.io.IOException("no asset")
        }
        var thrown: Exception? = null
        try {
            AutoconfigSeeder(exploding, { tmp.root }, { java.io.File(tmp.root, "legacy") }, "d", "RG Rotate").seedIfNeeded()
        } catch (e: Exception) {
            thrown = e
        }
        assertEquals(true, thrown is java.io.IOException)
        assertEquals(false, java.io.File(tmp.root, AutoconfigSeeder.STAMP_FILE).exists())
    }
}
