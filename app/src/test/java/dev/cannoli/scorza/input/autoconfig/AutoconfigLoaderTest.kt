package dev.cannoli.scorza.input.autoconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoconfigLoaderTest {

    private val xboxCfg = """
        input_device = "Xbox Wireless Controller"
        input_vendor_id = "1118"
        input_product_id = "2835"
        input_b_btn = "96"
    """.trimIndent()

    private val bitdoCfg = """
        input_device = "8BitDo Pro 2"
        input_vendor_id = "11720"
        input_product_id = "24582"
        input_b_btn = "96"
    """.trimIndent()

    private val source = MapCfgSource(
        mapOf(
            "autoconfig/android/Xbox Wireless Controller.cfg" to xboxCfg,
            "autoconfig/android/8BitDo Pro 2.cfg" to bitdoCfg
        )
    )

    @Test
    fun loadsAllEntries() {
        val loader = AutoconfigLoader(source)
        val entries = loader.entries()
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.deviceName == "Xbox Wireless Controller" })
        assertTrue(entries.any { it.deviceName == "8BitDo Pro 2" })
    }

    @Test
    fun cachesResultsBetweenCalls() {
        val loader = AutoconfigLoader(source)
        val first = loader.entries()
        val second = loader.entries()
        assertTrue(first === second)
    }

    @Test
    fun emptySourceReturnsEmptyList() {
        val loader = AutoconfigLoader(MapCfgSource(emptyMap()))
        assertEquals(0, loader.entries().size)
    }
}
