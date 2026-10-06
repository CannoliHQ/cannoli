package dev.cannoli.scorza.input.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MagicXKeyInjectionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val logged = mutableListOf<String>()

    private fun node(contents: String): File =
        File(tmp.root, "keycodetype").apply { writeText(contents) }

    private fun injection(node: File, maker: String = "MagicX") =
        MagicXKeyInjection(manufacturer = maker, node = node, log = { logged += it })

    @Test fun `the injecting mode is written to pure gamepad`() {
        val node = node("type=0\n")
        injection(node).suppress()
        assertEquals("2", node.readText())
    }

    @Test fun `the mode that also sends back is written too`() {
        val node = node("type=1\n")
        injection(node).suppress()
        assertEquals("2", node.readText())
    }

    @Test fun `a driver already in pure gamepad mode is left alone`() {
        val node = node("type=2\n")
        injection(node).suppress()
        assertEquals("type=2\n", node.readText())
        assertTrue("nothing changed, so nothing to say", logged.isEmpty())
    }

    @Test fun `another maker's device is never written`() {
        val node = node("type=0\n")
        injection(node, maker = "Retroid").suppress()
        assertEquals("type=0\n", node.readText())
    }

    @Test fun `a device without the node does nothing`() {
        injection(File(tmp.root, "absent")).suppress()
        assertTrue(logged.isEmpty())
    }

    @Test fun `a write the kernel refuses is survived and reported to the log`() {
        val node = node("type=0\n")
        node.setWritable(false, false)
        injection(node).suppress()
        assertTrue(logged.single().contains("refused"))
    }

    @Test fun `the log says what it changed`() {
        injection(node("type=0\n")).suppress()
        assertTrue(logged.single().contains("mode 0 -> 2"))
        assertFalse(logged.single().contains("refused"))
    }
}
