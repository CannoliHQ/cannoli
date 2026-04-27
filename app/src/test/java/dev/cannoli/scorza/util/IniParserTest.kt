package dev.cannoli.scorza.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IniParserTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Test fun `empty input yields empty data`() {
        val data = IniParser.parse("")
        assertTrue(data.sections.isEmpty())
        assertNull(data.get("any", "thing"))
    }

    @Test fun `keys before any section header land in the empty section`() {
        val data = IniParser.parse("foo = 1\nbar=2")
        assertEquals("1", data.get("", "foo"))
        assertEquals("2", data.get("", "bar"))
    }

    @Test fun `single section with multiple keys`() {
        val data = IniParser.parse(
            """
            [platforms]
            n64 = mupen64plus_libretro
            psx = pcsx_rearmed_libretro
            """.trimIndent()
        )
        val section = data.getSection("platforms")
        assertEquals(2, section.size)
        assertEquals("mupen64plus_libretro", section["n64"])
        assertEquals("pcsx_rearmed_libretro", section["psx"])
    }

    @Test fun `whitespace around keys values and section headers is trimmed`() {
        val data = IniParser.parse("[  s  ]\n   key   =   value with spaces   ")
        assertEquals("value with spaces", data.get("s", "key"))
    }

    @Test fun `semicolon comments are ignored`() {
        val data = IniParser.parse(
            """
            ; this is a comment
            [s]
            ; another comment
            real = yes
            """.trimIndent()
        )
        assertEquals("yes", data.get("s", "real"))
        assertEquals(1, data.getSection("s").size)
    }

    @Test fun `value containing equals sign is preserved past the first equals`() {
        val data = IniParser.parse("[s]\nurl = https://example.com/?a=1&b=2")
        assertEquals("https://example.com/?a=1&b=2", data.get("s", "url"))
    }

    @Test fun `lines without equals sign are skipped`() {
        val data = IniParser.parse("[s]\nbroken line\nkey = value")
        val section = data.getSection("s")
        assertEquals(1, section.size)
        assertEquals("value", section["key"])
    }

    @Test fun `duplicate keys keep the last value seen`() {
        val data = IniParser.parse("[s]\nx = 1\nx = 2\nx = 3")
        assertEquals("3", data.get("s", "x"))
    }

    @Test fun `multiple sections do not bleed into each other`() {
        val data = IniParser.parse(
            """
            [a]
            shared = from-a
            [b]
            shared = from-b
            only-b = b-only
            """.trimIndent()
        )
        assertEquals("from-a", data.get("a", "shared"))
        assertEquals("from-b", data.get("b", "shared"))
        assertNull(data.get("a", "only-b"))
        assertEquals("b-only", data.get("b", "only-b"))
    }

    @Test fun `empty section header still creates the entry`() {
        val data = IniParser.parse("[empty]\n[other]\nx = 1")
        assertTrue(data.sections.containsKey("empty"))
        assertTrue(data.getSection("empty").isEmpty())
    }

    @Test fun `getSection returns empty map for unknown section`() {
        val data = IniParser.parse("[s]\nkey = value")
        assertTrue(data.getSection("missing").isEmpty())
    }

    @Test fun `nonexistent file returns empty data without throwing`() {
        val file = File(tempFolder.root, "does-not-exist.ini")
        val data = IniParser.parse(file)
        assertTrue(data.sections.isEmpty())
    }

    @Test fun `file overload reads contents`() {
        val file = tempFolder.newFile("config.ini")
        file.writeText("[s]\nkey = file-value")
        val data = IniParser.parse(file)
        assertEquals("file-value", data.get("s", "key"))
    }

    @Test fun `windows style line endings are tolerated`() {
        val data = IniParser.parse("[s]\r\nkey = value\r\n")
        assertEquals("value", data.get("s", "key"))
    }

    @Test fun `key without value yields empty string`() {
        val data = IniParser.parse("[s]\nkey =")
        assertEquals("", data.get("s", "key"))
    }
}
