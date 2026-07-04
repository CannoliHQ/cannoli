package dev.cannoli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IniTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun parseReadsSectionsAndKeys() {
        val data = IniParser.parse(
            """
            [positions]
            nes/Game/guide.pdf=3
            ; a comment
            [zoom]
            nes/Game/guide.pdf=2
            """.trimIndent()
        )
        assertEquals("3", data.get("positions", "nes/Game/guide.pdf"))
        assertEquals("2", data.get("zoom", "nes/Game/guide.pdf"))
        assertNull(data.get("positions", "missing"))
    }

    @Test
    fun writeThenParseRoundTrips() {
        val file = File(tmp.root, "sub/positions.ini")
        IniWriter.write(file, mapOf("positions" to mapOf("a" to "1", "b" to "2")))
        val data = IniParser.parse(file)
        assertEquals("1", data.get("positions", "a"))
        assertEquals("2", data.get("positions", "b"))
    }
}
