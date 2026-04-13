package dev.cannoli.scorza.input.autoconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoconfigTranslatorTest {

    @Test
    fun translatesFaceButtonsToSNESPositionLayout() {
        val entry = RetroArchCfgEntry(
            deviceName = "Test",
            vendorId = 1, productId = 2,
            buttonBindings = mapOf(
                "b_btn" to 96,
                "a_btn" to 97,
                "y_btn" to 99,
                "x_btn" to 100
            )
        )
        val profile = AutoconfigTranslator.toProfile(entry)
        assertEquals(96, profile["btn_south"])
        assertEquals(97, profile["btn_east"])
        assertEquals(99, profile["btn_west"])
        assertEquals(100, profile["btn_north"])
    }

    @Test
    fun translatesShoulderAndTriggers() {
        val entry = RetroArchCfgEntry(
            deviceName = "Test", vendorId = 1, productId = 2,
            buttonBindings = mapOf(
                "l_btn" to 102, "r_btn" to 103,
                "l2_btn" to 104, "r2_btn" to 105,
                "l3_btn" to 106, "r3_btn" to 107
            )
        )
        val profile = AutoconfigTranslator.toProfile(entry)
        assertEquals(102, profile["btn_l"])
        assertEquals(103, profile["btn_r"])
        assertEquals(104, profile["btn_l2"])
        assertEquals(105, profile["btn_r2"])
        assertEquals(106, profile["btn_l3"])
        assertEquals(107, profile["btn_r3"])
    }

    @Test
    fun translatesDpadStartSelect() {
        val entry = RetroArchCfgEntry(
            deviceName = "Test", vendorId = 1, productId = 2,
            buttonBindings = mapOf(
                "up_btn" to 19, "down_btn" to 20, "left_btn" to 21, "right_btn" to 22,
                "start_btn" to 108, "select_btn" to 109
            )
        )
        val profile = AutoconfigTranslator.toProfile(entry)
        assertEquals(19, profile["btn_up"])
        assertEquals(20, profile["btn_down"])
        assertEquals(21, profile["btn_left"])
        assertEquals(22, profile["btn_right"])
        assertEquals(108, profile["btn_start"])
        assertEquals(109, profile["btn_select"])
    }

    @Test
    fun translatesMenuToggle() {
        val entry = RetroArchCfgEntry(
            deviceName = "Test", vendorId = 1, productId = 2,
            buttonBindings = mapOf("menu_toggle_btn" to 110)
        )
        val profile = AutoconfigTranslator.toProfile(entry)
        assertEquals(110, profile["btn_menu"])
    }

    @Test
    fun missingBindingsAreAbsentFromOutput() {
        val entry = RetroArchCfgEntry(
            deviceName = "Test", vendorId = 1, productId = 2,
            buttonBindings = mapOf("b_btn" to 96)
        )
        val profile = AutoconfigTranslator.toProfile(entry)
        assertEquals(1, profile.size)
        assertNull(profile["btn_east"])
    }
}
