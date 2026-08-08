package dev.cannoli.ricotta

import dev.cannoli.igm.RetroArchBridge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the wire format ricotta_sb_escaped produces. Every literal below is the exact output of
 * that C function, so changing the escaping means consciously rewriting these bytes.
 */
class CheatPayloadDecodeTest {

    @Test
    fun `decodes a plain row`() {
        assertEquals(
            listOf(RetroArchBridge.CheatRow(0, "Infinite Health", "8000-1234", false, true)),
            EmbeddedRetroArchBridge.decodeCheatRows("Infinite Health|8000-1234|0|1\n"),
        )
    }

    @Test
    fun `an escaped pipe stays inside its field`() {
        assertEquals(
            listOf(RetroArchBridge.CheatRow(0, "Pipe | in desc", "AA|BB", true, true)),
            EmbeddedRetroArchBridge.decodeCheatRows("Pipe \\| in desc|AA\\|BB|1|1\n"),
        )
    }

    @Test
    fun `an escaped backslash stays a backslash`() {
        assertEquals(
            listOf(RetroArchBridge.CheatRow(0, "Back\\slash", "C\\D", false, false)),
            EmbeddedRetroArchBridge.decodeCheatRows("Back\\\\slash|C\\\\D|0|0\n"),
        )
    }

    @Test
    fun `backslash n is a newline but an escaped backslash before n is not`() {
        assertEquals(
            listOf(
                RetroArchBridge.CheatRow(0, "Two\nlines", "E\nF", true, false),
                RetroArchBridge.CheatRow(1, "Literal\\nbackslash-n", "G\\nH", false, true),
            ),
            EmbeddedRetroArchBridge.decodeCheatRows(
                "Two\\nlines|E\\nF|1|0\nLiteral\\\\nbackslash-n|G\\\\nH|0|1\n",
            ),
        )
    }

    @Test
    fun `empty fields and a trailing escaped backslash decode`() {
        assertEquals(
            listOf(
                RetroArchBridge.CheatRow(0, "", "", false, true),
                RetroArchBridge.CheatRow(1, "Trailing back\\", "\\|", true, true),
            ),
            EmbeddedRetroArchBridge.decodeCheatRows("||0|1\nTrailing back\\\\|\\\\\\||1|1\n"),
        )
    }

    @Test
    fun `a malformed line drops without shifting the indexes after it`() {
        assertEquals(
            listOf(
                RetroArchBridge.CheatRow(0, "A", "AAAA", false, true),
                RetroArchBridge.CheatRow(2, "C", "CCCC", true, true),
            ),
            EmbeddedRetroArchBridge.decodeCheatRows("A|AAAA|0|1\nbroken\nC|CCCC|1|1\n"),
        )
    }

    @Test
    fun `an empty payload decodes to nothing`() {
        assertEquals(emptyList<RetroArchBridge.CheatRow>(), EmbeddedRetroArchBridge.decodeCheatRows(""))
    }
}
