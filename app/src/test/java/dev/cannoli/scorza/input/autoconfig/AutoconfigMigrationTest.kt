package dev.cannoli.scorza.input.autoconfig

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoconfigMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun migrate(bundledNames: Set<String> = emptySet()) =
        AutoconfigMigration({ tmp.root }, { bundledNames }).migrate()

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
        java.io.File(tmp.root, "my_pad.cfg").writeText("input_device = \"My Pad\"\n")
        migrate(bundledNames = setOf("retroid_nova.cfg"))
        assertEquals(true, java.io.File(tmp.root, "my_pad.cfg").exists())
    }

    @Test fun `an already migrated file is left alone`() {
        val f = java.io.File(tmp.root, "mine.cfg")
        f.writeText("input_device = \"Pad\"\ncannoli_source = \"USER\"\n")
        migrate()
        assertEquals(true, f.exists())
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
        assertEquals(true, java.io.File(tmp.root, "parked").list()!!.isNotEmpty())
    }

    @Test fun `an unreadable file survives migration`() {
        val f = java.io.File(tmp.root, "locked.cfg")
        f.writeText("input_device = \"Pad\"\n")
        f.setReadable(false)
        migrate(bundledNames = setOf("locked.cfg"))
        assertEquals(true, f.exists())
        f.setReadable(true)
    }
}
