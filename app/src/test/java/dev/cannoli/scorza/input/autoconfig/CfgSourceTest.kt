package dev.cannoli.scorza.input.autoconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

class CfgSourceTest {

    private val first = MapCfgSource(
        mapOf("autoconfig/android/PadA.cfg" to "input_device = \"Pad A\"\n")
    )
    private val second = MapCfgSource(
        mapOf("autoconfig/cannoli/AYN_Thor.cfg" to "input_device = \"Odin Controller\"\n")
    )

    @Test
    fun `composite lists files from all sources`() {
        val composite = CompositeCfgSource(listOf(first, second))

        val files = composite.listCfgFiles()

        assertEquals(2, files.size)
        assertTrue(files.contains("autoconfig/android/PadA.cfg"))
        assertTrue(files.contains("autoconfig/cannoli/AYN_Thor.cfg"))
    }

    @Test
    fun `composite open reads a file that only the second source lists`() {
        val composite = CompositeCfgSource(listOf(first, second))

        val content = composite.open("autoconfig/cannoli/AYN_Thor.cfg").bufferedReader().readText()

        assertEquals("input_device = \"Odin Controller\"\n", content)
    }

    @Test(expected = FileNotFoundException::class)
    fun `composite open throws when no source has the file`() {
        CompositeCfgSource(listOf(first, second)).open("autoconfig/cannoli/Missing.cfg")
    }
}
