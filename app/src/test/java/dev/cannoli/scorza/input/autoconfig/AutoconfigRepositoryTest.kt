package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoconfigRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun repo() = AutoconfigRepository { tmp.root }

    private fun stagedRepo(root: () -> java.io.File?, staging: java.io.File) =
        AutoconfigRepository(stagingDirProvider = { staging }, dirProvider = root)

    private fun cfgWriterSampleMapping(id: String) = DeviceMapping(
        id = id,
        displayName = "Living Room Pad",
        match = DeviceMatchRule(
            name = "8BitDo Pro 2",
            vendorId = 11720,
            productId = 24582,
        ),
        bindings = mapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
            CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97)),
        ),
        menuConfirm = CanonicalButton.BTN_SOUTH,
        menuBack = CanonicalButton.BTN_EAST,
        glyphStyle = GlyphStyle.entries.last(),
        source = MappingSource.USER_WIZARD,
        userEdited = true,
    )

    @Test
    fun `lists parsed entries with file identity`() {
        tmp.newFile("PadA.cfg").writeText("input_device = \"Pad A\"\ninput_b_btn = \"96\"\n")
        tmp.newFile("broken.cfg").writeText("not a cfg at all {{{")
        tmp.newFile("notes.txt").writeText("ignored")
        val entries = repo().listEntries()
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.fileName == "PadA.cfg" && it.deviceName == "Pad A" })
    }

    @Test
    fun `lists entries sorted by file name so equal matches tie-break the same everywhere`() {
        // Directory listing order differs between ext4 and exFAT, so two cfgs that match a pad
        // equally well would otherwise hand the same controller different layouts per device.
        for (name in listOf("zeta.cfg", "delta.cfg", "alpha.cfg", "mu.cfg")) {
            tmp.newFile(name).writeText("input_device = \"Same Pad\"\ninput_b_btn = \"96\"\n")
        }
        assertEquals(
            listOf("alpha.cfg", "delta.cfg", "mu.cfg", "zeta.cfg"),
            repo().listEntries().map { it.fileName },
        )
    }

    @Test
    fun `save writes atomically and findById reads it back`() {
        val r = repo()
        r.save(cfgWriterSampleMapping(id = "PadA"))
        val entry = r.findById("PadA")
        assertEquals("PadA.cfg", entry?.fileName)
        assertEquals(CfgProvenance.USER, entry!!.provenance)
        assertEquals(0, tmp.root.listFiles { f -> f.name.endsWith(".tmp") }!!.size)
    }

    @Test
    fun `save invalidates the list cache`() {
        val r = repo()
        assertEquals(0, r.listEntries().size)
        r.save(cfgWriterSampleMapping(id = "PadA"))
        assertEquals(1, r.listEntries().size)
    }

    @Test
    fun `delete removes the file`() {
        val r = repo()
        r.save(cfgWriterSampleMapping(id = "PadA"))
        r.delete("PadA")
        assertNull(r.findById("PadA"))
        assertEquals(0, r.listEntries().size)
    }

    @Test
    fun `saves into staging when first run has not chosen a root yet`() {
        val staging = tmp.newFolder("staging")
        stagedRepo(root = { null }, staging = staging).save(cfgWriterSampleMapping("pad-1"))
        assertTrue(java.io.File(staging, "pad-1.cfg").isFile)
    }

    @Test
    fun `reads back a staged cfg, so a pad configured before the grant is found again`() {
        val staging = tmp.newFolder("staging")
        val repo = stagedRepo(root = { null }, staging = staging)
        repo.save(cfgWriterSampleMapping("pad-1"))
        assertTrue(repo.stagedEntries().any { it.fileName == "pad-1.cfg" })
    }

    @Test
    fun `staged entries stay out of the seeded listing, which the resolver reads as complete`() {
        val staging = tmp.newFolder("staging")
        val root = tmp.newFolder("root")
        stagedRepo(root = { null }, staging = staging).save(cfgWriterSampleMapping("pad-1"))
        val repo = stagedRepo(root = { root }, staging = staging)
        assertEquals(emptyList<RetroArchCfgEntry>(), repo.listEntries())
        assertTrue(repo.stagedEntries().any { it.fileName == "pad-1.cfg" })
    }

    @Test
    fun `findById sees a staged cfg as well as a seeded one`() {
        val staging = tmp.newFolder("staging")
        stagedRepo(root = { null }, staging = staging).save(cfgWriterSampleMapping("pad-1"))
        assertTrue(stagedRepo(root = { null }, staging = staging).findById("pad-1") != null)
    }

    @Test
    fun `prefers the chosen root over staging once one exists`() {
        val staging = tmp.newFolder("staging")
        val root = tmp.newFolder("root")
        stagedRepo(root = { null }, staging = staging).save(cfgWriterSampleMapping("staged"))
        stagedRepo(root = { root }, staging = staging).save(cfgWriterSampleMapping("real"))
        assertTrue(java.io.File(root, "real.cfg").isFile)
        assertNull(java.io.File(staging, "real.cfg").takeIf { it.isFile })
    }

    @Test
    fun `promoteStaging moves staged cfgs onto the card and empties staging`() {
        val staging = tmp.newFolder("staging")
        val root = tmp.newFolder("root")
        stagedRepo(root = { null }, staging = staging).save(cfgWriterSampleMapping("pad-1"))

        stagedRepo(root = { root }, staging = staging).promoteStaging()

        assertTrue(java.io.File(root, "pad-1.cfg").isFile)
        assertTrue(!staging.exists())
    }

    @Test
    fun `promoteStaging is a no-op while the root is still unresolved`() {
        val staging = tmp.newFolder("staging")
        stagedRepo(root = { null }, staging = staging).save(cfgWriterSampleMapping("pad-1"))

        stagedRepo(root = { null }, staging = staging).promoteStaging()

        assertTrue(java.io.File(staging, "pad-1.cfg").isFile)
    }

    @Test
    fun `a staged cfg wins a name already on the card, being the user's own work`() {
        val staging = tmp.newFolder("staging")
        val root = tmp.newFolder("root")
        java.io.File(root, "pad-1.cfg").writeText("input_device = \"Older\"\n")
        stagedRepo(root = { null }, staging = staging).save(cfgWriterSampleMapping("pad-1"))

        stagedRepo(root = { root }, staging = staging).promoteStaging()

        assertTrue(java.io.File(root, "pad-1.cfg").readText().contains("Living Room Pad"))
    }
}
