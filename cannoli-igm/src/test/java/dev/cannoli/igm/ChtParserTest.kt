package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChtParserTest {

    @Test
    fun parsesEmuHandlerTriples() {
        val text = """
            cheat0_desc = "Infinite Lives"
            cheat0_code = "AAAA-BBBB+CCCC-DDDD"
            cheat0_enable = false
            cheat1_desc = "Max Money"
            cheat1_code = "1234-5678"
            cheat1_enable = true
            cheats = 2
        """.trimIndent()
        val cheats = ChtParser.parse(text)
        assertEquals(2, cheats.size)
        assertEquals("Infinite Lives", cheats[0].desc)
        assertEquals("AAAA-BBBB+CCCC-DDDD", cheats[0].code)
        assertFalse(cheats[0].enable)
        assertTrue(cheats[1].enable)
        assertTrue(cheats[0].isEmuHandler)
    }

    @Test
    fun cheatsCountGatesEntries() {
        val text = """
            cheats = 1
            cheat0_desc = "Kept"
            cheat0_code = "AAAA"
            cheat1_desc = "Ignored"
            cheat1_code = "BBBB"
        """.trimIndent()
        val cheats = ChtParser.parse(text)
        assertEquals(1, cheats.size)
        assertEquals("Kept", cheats[0].desc)
    }

    @Test
    fun missingCheatsKeyYieldsEmpty() {
        assertEquals(emptyList<CheatEntry>(), ChtParser.parse("cheat0_desc = \"X\""))
    }

    @Test
    fun defaultsMatchRetroArch() {
        val c = ChtParser.parse("cheats = 1")[0]
        assertEquals(CheatEntry.HANDLER_EMU, c.handler)
        assertFalse(c.enable)
        assertEquals(CheatEntry.TYPE_SET_TO_VALUE, c.cheatType)
        assertEquals(3, c.memorySearchSize)
        assertEquals(1L, c.repeatCount)
        assertEquals(0L, c.repeatAddToValue)
        assertEquals(1L, c.repeatAddToAddress)
        assertEquals(0L, c.address)
        assertEquals(0L, c.value)
        assertFalse(c.bigEndian)
    }

    @Test
    fun parsesRetroHandlerFields() {
        val text = """
            cheats = 1
            cheat0_desc = "Infinite Credits"
            cheat0_code = ""
            cheat0_enable = true
            cheat0_handler = 1
            cheat0_address = 76912
            cheat0_value = 9
            cheat0_cheat_type = 1
            cheat0_memory_search_size = 3
            cheat0_big_endian = false
            cheat0_address_bit_position = 255
            cheat0_repeat_count = 4
            cheat0_repeat_add_to_address = 2
            cheat0_repeat_add_to_value = 1
        """.trimIndent()
        val c = ChtParser.parse(text)[0]
        assertEquals(CheatEntry.HANDLER_RETRO, c.handler)
        assertFalse(c.isEmuHandler)
        assertEquals(76912L, c.address)
        assertEquals(9L, c.value)
        assertEquals(255L, c.addressBitPosition)
        assertEquals(4L, c.repeatCount)
        assertEquals(2L, c.repeatAddToAddress)
        assertEquals(1L, c.repeatAddToValue)
    }

    @Test
    fun acceptsHexNumbersAndEnableAsOne() {
        val text = """
            cheats = 1
            cheat0_enable = 1
            cheat0_handler = 1
            cheat0_address = 0x12C70
        """.trimIndent()
        val c = ChtParser.parse(text)[0]
        assertTrue(c.enable)
        assertEquals(0x12C70L, c.address)
    }

    @Test
    fun garbageNumbersParseAsZeroLikeStrtoul() {
        val text = """
            cheats = 1
            cheat0_address = notanumber
            cheat0_value = 12abc
        """.trimIndent()
        val c = ChtParser.parse(text)[0]
        assertEquals(0L, c.address)
        assertEquals(12L, c.value)
    }

    @Test
    fun rumbleFieldsRoundTrip() {
        val text = """
            cheats = 1
            cheat0_handler = 1
            cheat0_rumble_type = 5
            cheat0_rumble_value = 100
            cheat0_rumble_port = 0
            cheat0_rumble_primary_strength = 65535
            cheat0_rumble_primary_duration = 300
            cheat0_rumble_secondary_strength = 32000
            cheat0_rumble_secondary_duration = 150
        """.trimIndent()
        val c = ChtParser.parse(text)[0]
        assertEquals(5L, c.rumbleType)
        assertEquals(100L, c.rumbleValue)
        assertEquals(65535L, c.rumblePrimaryStrength)
        assertEquals(150L, c.rumbleSecondaryDuration)
    }

    @Test
    fun displayLabelFallsBackToCode() {
        val text = """
            cheats = 2
            cheat0_desc = ""
            cheat0_code = "AAAA-BBBB"
            cheat1_desc = "Named"
            cheat1_code = "CCCC"
        """.trimIndent()
        val cheats = ChtParser.parse(text)
        assertEquals("AAAA-BBBB", cheats[0].displayLabel)
        assertEquals("Named", cheats[1].displayLabel)
    }

    @Test
    fun blankLinesAndCommentsIgnored() {
        val text = "\n# comment line\ncheats = 1\n\ncheat0_desc = \"X\"\n"
        assertEquals("X", ChtParser.parse(text)[0].desc)
    }
}
