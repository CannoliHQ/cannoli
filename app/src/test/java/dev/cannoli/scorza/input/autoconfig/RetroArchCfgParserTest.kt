package dev.cannoli.scorza.input.autoconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetroArchCfgParserTest {

    private val sample = """
        input_driver = "android"
        input_device = "8BitDo Pro 2"
        input_vendor_id = "11720"
        input_product_id = "24582"
        input_b_btn = "96"
        input_a_btn = "97"
        input_up_btn = "19"
        input_l_x_plus_axis = "+0"
        # a comment line
        input_unknown_field = "ignored"
    """.trimIndent()

    @Test
    fun parsesDeviceNameAndIds() {
        val entry = RetroArchCfgParser.parse(sample)
        assertEquals("8BitDo Pro 2", entry.deviceName)
        assertEquals(11720, entry.vendorId)
        assertEquals(24582, entry.productId)
    }

    @Test
    fun parsesAllSupportedButtonBindings() {
        val entry = RetroArchCfgParser.parse(sample)
        assertEquals(96, entry.buttonBindings["b_btn"])
        assertEquals(97, entry.buttonBindings["a_btn"])
        assertEquals(19, entry.buttonBindings["up_btn"])
    }

    @Test
    fun ignoresAxisAndUnknownKeys() {
        val entry = RetroArchCfgParser.parse(sample)
        assertNull(entry.buttonBindings["l_x_plus_axis"])
        assertNull(entry.buttonBindings["unknown_field"])
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val withComments = """
            # leading comment
            input_device = "Test"

            input_b_btn = "1"
        """.trimIndent()
        val entry = RetroArchCfgParser.parse(withComments)
        assertEquals("Test", entry.deviceName)
        assertEquals(1, entry.buttonBindings["b_btn"])
    }

    @Test
    fun missingDeviceNameDefaultsEmpty() {
        val entry = RetroArchCfgParser.parse("""input_b_btn = "1"""")
        assertEquals("", entry.deviceName)
        assertNull(entry.vendorId)
    }

    @Test
    fun skipsHatValuesInButtonBindings() {
        val hatSample = """
            input_device = "Hat Pad"
            input_up_btn = "h0up"
            input_down_btn = "19"
        """.trimIndent()
        val entry = RetroArchCfgParser.parse(hatSample)
        assertNull(entry.buttonBindings["up_btn"])
        assertEquals(19, entry.buttonBindings["down_btn"])
    }

    @Test
    fun parsesFilesWithCrlfLineEndings() {
        val crlfSample = "input_device = \"Test\"\r\ninput_b_btn = \"1\"\r\n"
        val entry = RetroArchCfgParser.parse(crlfSample)
        assertEquals("Test", entry.deviceName)
        assertEquals(1, entry.buttonBindings["b_btn"])
    }
}
