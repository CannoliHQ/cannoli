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

    @Test
    fun parsesAxisLinesForL2AndSticks() {
        val cfg = """
            input_device = "Test"
            input_vendor_id = "1"
            input_product_id = "2"
            input_l2_axis = "+5"
            input_l_x_plus_axis = "+0"
            input_l_x_minus_axis = "-0"
        """.trimIndent()
        val entry = RetroArchCfgParser.parse(cfg)
        org.junit.Assert.assertEquals(AxisRef(5, +1), entry.axisBindings["l2_axis"])
        org.junit.Assert.assertEquals(AxisRef(0, +1), entry.axisBindings["l_x_plus_axis"])
        org.junit.Assert.assertEquals(AxisRef(0, -1), entry.axisBindings["l_x_minus_axis"])
    }

    @Test
    fun parsesHatNotationForDpadButtons() {
        val cfg = """
            input_device = "Test"
            input_vendor_id = "1"
            input_product_id = "2"
            input_up_btn = "h0up"
            input_down_btn = "h0down"
            input_left_btn = "h0left"
            input_right_btn = "h0right"
            input_a_btn = "97"
        """.trimIndent()
        val entry = RetroArchCfgParser.parse(cfg)
        org.junit.Assert.assertEquals(HatRef(0, CfgHatDirection.UP), entry.hatBindings["up_btn"])
        org.junit.Assert.assertEquals(HatRef(0, CfgHatDirection.DOWN), entry.hatBindings["down_btn"])
        org.junit.Assert.assertEquals(HatRef(0, CfgHatDirection.LEFT), entry.hatBindings["left_btn"])
        org.junit.Assert.assertEquals(HatRef(0, CfgHatDirection.RIGHT), entry.hatBindings["right_btn"])
        // Integer-valued button still parses as a normal button binding.
        org.junit.Assert.assertEquals(97, entry.buttonBindings["a_btn"])
        // Up/Down/Left/Right are NOT in buttonBindings (their values were hat values).
        org.junit.Assert.assertNull(entry.buttonBindings["up_btn"])
        org.junit.Assert.assertNull(entry.buttonBindings["down_btn"])
    }

    @Test
    fun `parses cannoli metadata keys`() {
        val entry = RetroArchCfgParser.parse(
            """
            input_device = "8BitDo Pro 2"
            input_device_display_name = "Living Room Pad"
            input_vendor_id = "11720"
            input_product_id = "24582"
            input_b_btn = "96"
            cannoli_user = "true"
            cannoli_confirm_button = "BTN_SOUTH"
            cannoli_glyph_style = "PLUMBER"
            cannoli_exclude_from_gameplay = "true"
            cannoli_descriptor = "abc123"
            cannoli_build_model = "Retroid Pocket 5"
            cannoli_source_mask = "16778513"
            cannoli_default_controller_type = "517"
            """.trimIndent(),
            fileName = "8BitDo_Pro_2.cfg",
        )
        assertEquals("Living Room Pad", entry.displayName)
        org.junit.Assert.assertTrue(entry.cannoliUser)
        assertEquals("BTN_SOUTH", entry.confirmButton)
        assertEquals("PLUMBER", entry.glyphStyle)
        org.junit.Assert.assertTrue(entry.excludeFromGameplay)
        assertEquals("abc123", entry.descriptor)
        assertEquals("Retroid Pocket 5", entry.buildModel)
        assertEquals(16778513, entry.sourceMask)
        assertEquals(517, entry.defaultControllerType)
        assertEquals("8BitDo_Pro_2.cfg", entry.fileName)
    }

    @Test
    fun `cannoli fields default to absent`() {
        val entry = RetroArchCfgParser.parse("input_device = \"Pad\"\ninput_b_btn = \"96\"")
        assertNull(entry.displayName)
        org.junit.Assert.assertFalse(entry.cannoliUser)
        assertNull(entry.confirmButton)
        org.junit.Assert.assertFalse(entry.excludeFromGameplay)
        assertNull(entry.cannoliMenuKeycodes)
        assertNull(entry.fileName)
    }

    @Test
    fun `parses the cannoli menu keycode list`() {
        val entry = RetroArchCfgParser.parse(
            """
            input_device = "Pad"
            input_menu_toggle_btn = "318"
            cannoli_menu_keycodes = "318,4"
            """.trimIndent()
        )
        assertEquals(listOf(318, 4), entry.cannoliMenuKeycodes)
    }

    @Test
    fun `an empty cannoli menu keycode list parses as a cleared menu`() {
        val entry = RetroArchCfgParser.parse(
            """
            input_device = "Pad"
            cannoli_menu_keycodes = ""
            """.trimIndent()
        )
        assertEquals(emptyList<Int>(), entry.cannoliMenuKeycodes)
    }

    @Test
    fun `parses the curated AYN Thor cfg`() {
        val entry = RetroArchCfgParser.parse(AYN_THOR_CFG)
        assertEquals("AYN Thor", entry.buildModel)
        assertEquals("PLUMBER", entry.glyphStyle)
        assertEquals(
            dev.cannoli.scorza.input.CanonicalButton.BTN_EAST,
            dev.cannoli.scorza.input.CanonicalButton.valueOf(entry.confirmButton!!),
        )
        assertEquals(96, entry.buttonBindings["a_btn"])
        assertEquals(97, entry.buttonBindings["b_btn"])
    }

    @Test fun `parses cannoli_source and cannoli_builtin`() {
        val entry = RetroArchCfgParser.parse(
            """
            input_device = "Retroid Pocket Controller"
            cannoli_source = "INPUT_DB"
            cannoli_builtin = "true"
            """.trimIndent()
        )
        assertEquals(CfgProvenance.INPUT_DB, entry.provenance)
        assertEquals(true, entry.builtin)
    }

    @Test fun `absent provenance and builtin parse as null`() {
        val entry = RetroArchCfgParser.parse("input_device = \"Pad\"\n")
        assertEquals(null, entry.provenance)
        assertEquals(null, entry.builtin)
    }

    @Test fun `unknown provenance value parses as null`() {
        val entry = RetroArchCfgParser.parse("cannoli_source = \"NONSENSE\"\n")
        assertEquals(null, entry.provenance)
    }

    companion object {
        private val AYN_THOR_CFG = """
            input_driver = "android"
            input_device = "Odin Controller"
            input_device_display_name = "AYN Thor"
            input_vendor_id = "8224"
            input_product_id = "273"
            cannoli_build_model = "AYN Thor"
            cannoli_glyph_style = "PLUMBER"
            cannoli_confirm_button = "BTN_EAST"
            input_b_btn = "97"
            input_b_btn_label = "B"
            input_a_btn = "96"
            input_a_btn_label = "A"
            input_x_btn = "99"
            input_x_btn_label = "X"
            input_y_btn = "100"
            input_y_btn_label = "Y"
            input_select_btn = "109"
            input_select_btn_label = "Select"
            input_start_btn = "108"
            input_start_btn_label = "Start"
            input_up_btn = "h0up"
            input_up_btn_label = "D-Pad Up"
            input_down_btn = "h0down"
            input_down_btn_label = "D-Pad Down"
            input_left_btn = "h0left"
            input_left_btn_label = "D-Pad Left"
            input_right_btn = "h0right"
            input_right_btn_label = "D-Pad Right"
            input_l_btn = "102"
            input_l_btn_label = "L1"
            input_r_btn = "103"
            input_r_btn_label = "R1"
            input_l2_axis = "+8"
            input_l2_axis_label = "L2"
            input_r2_axis = "+9"
            input_r2_axis_label = "R2"
            input_l3_btn = "106"
            input_l3_btn_label = "Left Thumb"
            input_r3_btn = "107"
            input_r3_btn_label = "Right Thumb"
            input_l_x_plus_axis = "+0"
            input_l_x_plus_axis_label = "Left Stick Right"
            input_l_x_minus_axis = "-0"
            input_l_x_minus_axis_label = "Left Stick Left"
            input_l_y_plus_axis = "+1"
            input_l_y_plus_axis_label = "Left Stick Up"
            input_l_y_minus_axis = "-1"
            input_l_y_minus_axis_label = "Left Stick Down"
            input_r_x_plus_axis = "+2"
            input_r_x_plus_axis_label = "Right Stick Right"
            input_r_x_minus_axis = "-2"
            input_r_x_minus_axis_label = "Right Stick Left"
            input_r_y_plus_axis = "+3"
            input_r_y_plus_axis_label = "Right Stick Up"
            input_r_y_minus_axis = "-3"
            input_r_y_minus_axis_label = "Right Stick Down"
            input_menu_toggle_btn = "4"
            input_menu_toggle_btn_label = "Back"
        """.trimIndent()
    }
}
