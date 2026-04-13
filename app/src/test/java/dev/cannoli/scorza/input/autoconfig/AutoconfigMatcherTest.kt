package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.ControllerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoconfigMatcherTest {

    private val entries = listOf(
        RetroArchCfgEntry("Xbox Wireless Controller", 1118, 2835, mapOf("b_btn" to 96)),
        RetroArchCfgEntry("8BitDo Pro 2", 11720, 24582, mapOf("b_btn" to 96)),
        RetroArchCfgEntry("Generic BT Gamepad", 9999, 8888, mapOf("b_btn" to 190))
    )
    private val matcher = AutoconfigMatcher(entries)

    @Test
    fun exactNameAndVidPidScoresHighest() {
        val id = ControllerIdentity("desc", "Xbox Wireless Controller", 1118, 2835)
        val match = matcher.match(id)
        assertEquals("Xbox Wireless Controller", match?.deviceName)
    }

    @Test
    fun vidPidMatchBeatsNameOnly() {
        val id = ControllerIdentity("desc", "Unknown Stick", 1118, 2835)
        val match = matcher.match(id)
        assertEquals("Xbox Wireless Controller", match?.deviceName)
    }

    @Test
    fun nameMatchAloneIsNotEnough() {
        val id = ControllerIdentity("desc", "Xbox Wireless Controller", 4444, 5555)
        val match = matcher.match(id)
        assertNull(match)
    }

    @Test
    fun noMatchReturnsNull() {
        val id = ControllerIdentity("desc", "Nothing", 1, 2)
        val match = matcher.match(id)
        assertNull(match)
    }

    @Test
    fun zeroVidPidWithNameAloneIsNotEnough() {
        val id = ControllerIdentity("desc", "8BitDo Pro 2", 0, 0)
        val match = matcher.match(id)
        assertNull(match)
    }
}
