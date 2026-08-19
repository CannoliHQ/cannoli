package dev.cannoli.scorza.input.autoconfig

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoconfigMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun migrate(bundledNames: Set<String> = emptySet()) =
        AutoconfigMigration({ tmp.root }, { bundledNames }).migrate()

    private fun snapshot(dir: java.io.File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readText() }

    private fun restoreReadability(dir: java.io.File) {
        dir.walkTopDown().forEach { it.setReadable(true) }
    }

    @Test fun `a legacy user file becomes USER and loses its descriptor suffix`() {
        java.io.File(tmp.root, "ra_wireless_controller_5b12f6.cfg").writeText(
            """
            input_device = "Wireless Controller"
            input_vendor_id = "1356"
            input_product_id = "2508"
            cannoli_user = "true"
            cannoli_descriptor = "2484723787b4688b31e1f2488e403f5fb34c063b"
            """.trimIndent()
        )
        migrate()
        val out = java.io.File(tmp.root, "ra_wireless_controller.cfg")
        assertEquals(true, out.exists())
        val text = out.readText()
        assertEquals(true, text.contains("cannoli_source = \"USER\""))
        assertEquals(false, text.contains("cannoli_descriptor"))
        assertEquals(false, java.io.File(tmp.root, "ra_wireless_controller_5b12f6.cfg").exists())
    }

    @Test fun `an unkeyed cfg whose name we ship is deleted for re-seeding`() {
        java.io.File(tmp.root, "retroid_nova.cfg").writeText("input_device = \"Retroid Pocket Controller\"\n")
        migrate(bundledNames = setOf("retroid_nova.cfg"))
        assertEquals(false, java.io.File(tmp.root, "retroid_nova.cfg").exists())
    }

    @Test fun `an unkeyed cfg whose name we do not ship survives untouched`() {
        val original = "input_device = \"My Pad\"\n"
        java.io.File(tmp.root, "my_pad.cfg").writeText(original)
        migrate(bundledNames = setOf("retroid_nova.cfg"))
        val f = java.io.File(tmp.root, "my_pad.cfg")
        assertEquals(true, f.exists())
        assertEquals(original, f.readText())
    }

    @Test fun `an already migrated file is left alone`() {
        val original = "input_device = \"Pad\"\ncannoli_source = \"USER\"\n"
        val f = java.io.File(tmp.root, "mine.cfg")
        f.writeText(original)
        migrate()
        assertEquals(true, f.exists())
        assertEquals(original, f.readText())
    }

    @Test fun `colliding user files keep the newest and park the other`() {
        val a = java.io.File(tmp.root, "ra_pad_aaaaaa.cfg")
        val b = java.io.File(tmp.root, "ra_pad_bbbbbb.cfg")
        a.writeText("input_device = \"Pad\"\ncannoli_user = \"true\"\ninput_b_btn = \"96\"\n")
        b.writeText("input_device = \"Pad\"\ncannoli_user = \"true\"\ninput_b_btn = \"97\"\n")
        a.setLastModified(1_000L)
        b.setLastModified(2_000L)
        migrate()
        assertEquals(true, java.io.File(tmp.root, "ra_pad.cfg").readText().contains("97"))
        val parked = java.io.File(tmp.root, "parked").listFiles()!!
        assertEquals(1, parked.size)
        assertEquals(true, parked[0].readText().contains("96"))
    }

    @Test fun `three colliding user files keep the newest and park both others without overwriting`() {
        val a = java.io.File(tmp.root, "ra_pad_aaaaaa.cfg")
        val b = java.io.File(tmp.root, "ra_pad_bbbbbb.cfg")
        val c = java.io.File(tmp.root, "ra_pad_cccccc.cfg")
        a.writeText("input_device = \"Pad\"\ncannoli_user = \"true\"\ninput_b_btn = \"10\"\n")
        b.writeText("input_device = \"Pad\"\ncannoli_user = \"true\"\ninput_b_btn = \"20\"\n")
        c.writeText("input_device = \"Pad\"\ncannoli_user = \"true\"\ninput_b_btn = \"30\"\n")
        a.setLastModified(1_000L)
        b.setLastModified(2_000L)
        c.setLastModified(3_000L)
        migrate()
        assertEquals(true, java.io.File(tmp.root, "ra_pad.cfg").readText().contains("\"30\""))
        val parked = java.io.File(tmp.root, "parked").listFiles()!!
        assertEquals(2, parked.size)
        val parkedContents = parked.map { it.readText() }
        assertEquals(true, parkedContents.any { it.contains("\"10\"") })
        assertEquals(true, parkedContents.any { it.contains("\"20\"") })
    }

    @Test fun `a rename onto an already-migrated target parks the target instead of deleting it`() {
        java.io.File(tmp.root, "ra_pad.cfg").writeText(
            "input_device = \"Pad\"\ncannoli_source = \"USER\"\ninput_b_btn = \"55\"\n"
        )
        java.io.File(tmp.root, "ra_pad_aaaaaa.cfg").writeText(
            "input_device = \"Pad\"\ncannoli_user = \"true\"\ninput_b_btn = \"66\"\n"
        )
        migrate()
        assertEquals(true, java.io.File(tmp.root, "ra_pad.cfg").readText().contains("66"))
        val parked = java.io.File(tmp.root, "parked").listFiles()!!
        assertEquals(1, parked.size)
        assertEquals(true, parked[0].readText().contains("55"))
    }

    @Test fun `a rename onto an unreadable target parks the target instead of deleting it`() {
        val target = java.io.File(tmp.root, "ra_pad.cfg")
        target.writeText("input_device = \"Pad\"\ninput_b_btn = \"55\"\n")
        java.io.File(tmp.root, "ra_pad_aaaaaa.cfg").writeText(
            "input_device = \"Pad\"\ncannoli_user = \"true\"\ninput_b_btn = \"66\"\n"
        )
        target.setReadable(false)
        try {
            migrate(bundledNames = setOf("ra_pad.cfg"))
            assertEquals(true, java.io.File(tmp.root, "ra_pad.cfg").readText().contains("66"))
            val parked = java.io.File(tmp.root, "parked").listFiles()!!
            assertEquals(1, parked.size)
        } finally {
            restoreReadability(tmp.root)
        }
    }

    @Test fun `a rename onto a foreign target parks the target instead of deleting it`() {
        java.io.File(tmp.root, "ra_pad.cfg").writeText("input_device = \"Pad\"\ninput_b_btn = \"55\"\n")
        java.io.File(tmp.root, "ra_pad_aaaaaa.cfg").writeText(
            "input_device = \"Pad\"\ncannoli_user = \"true\"\ninput_b_btn = \"66\"\n"
        )
        migrate(bundledNames = setOf("something_else.cfg"))
        assertEquals(true, java.io.File(tmp.root, "ra_pad.cfg").readText().contains("66"))
        val parked = java.io.File(tmp.root, "parked").listFiles()!!
        assertEquals(1, parked.size)
        assertEquals(true, parked[0].readText().contains("55"))
    }

    @Test fun `running migrate twice changes nothing on the second run`() {
        java.io.File(tmp.root, "ra_wireless_controller_5b12f6.cfg").writeText(
            "input_device = \"Wireless Controller\"\ncannoli_user = \"true\"\n"
        )
        migrate()
        val before = snapshot(tmp.root)
        migrate()
        assertEquals(before, snapshot(tmp.root))
    }

    // The gate this proves: the seeder writes fresh curated cfgs into this same directory right
    // after migrate() runs, and those cfgs carry no cannoli_source yet. Without the one-shot stamp,
    // a second migrate() call on a later boot would see this file as an unkeyed pre-v2 leftover
    // whose name happens to be bundled, and delete it with nothing to restore it.
    @Test fun `migrating again after the seeder seeds a fresh curated cfg does not delete it`() {
        val bundledNames = setOf("retroid_nova.cfg")
        migrate(bundledNames)
        val seeded = java.io.File(tmp.root, "retroid_nova.cfg")
        val content = "input_device = \"Retroid Pocket Controller\"\n"
        seeded.writeText(content)
        migrate(bundledNames)
        assertEquals(true, seeded.exists())
        assertEquals(content, seeded.readText())
    }

    @Test fun `an unreadable file survives migration`() {
        val f = java.io.File(tmp.root, "locked.cfg")
        f.writeText("input_device = \"Pad\"\n")
        f.setReadable(false)
        try {
            migrate(bundledNames = setOf("locked.cfg"))
            assertEquals(true, f.exists())
        } finally {
            restoreReadability(tmp.root)
        }
    }
}
