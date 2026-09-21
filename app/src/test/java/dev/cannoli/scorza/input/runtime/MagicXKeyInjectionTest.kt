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

    // The default the driver boots into, and the one that makes the first-run wizard impossible.
    @Test fun `the injecting mode is written to pure gamepad`() {
        val node = node("type=0\n")
        injection(node).suppress()
        assertEquals("2", node.readText())
    }

    // Mode 1 sends back with B as well, so it is no more usable than mode 0.
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

    // Every other handheld. The path is a MediaTek keypad address, and the value means something
    // else, or nothing, on a driver that is not this one.
    @Test fun `another maker's device is never written`() {
        val node = node("type=0\n")
        injection(node, maker = "Retroid").suppress()
        assertEquals("type=0\n", node.readText())
    }

    @Test fun `a device without the node does nothing`() {
        injection(File(tmp.root, "absent")).suppress()
        assertTrue(logged.isEmpty())
    }

    // A kernel that refuses the write is not something a player can act on, so it must not throw
    // on the way through onResume.
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
