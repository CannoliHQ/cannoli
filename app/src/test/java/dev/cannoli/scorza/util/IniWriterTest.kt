package dev.cannoli.scorza.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IniWriterTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Test fun `writes a single section with key value pairs`() {
        val file = File(tempFolder.root, "out.ini")
        IniWriter.write(file, mapOf("frontend" to mapOf("scaling" to "INTEGER", "shader_preset" to "foo.glslp")))

        assertTrue(file.exists())
        val parsed = IniParser.parse(file)
        assertEquals("INTEGER", parsed.get("frontend", "scaling"))
        assertEquals("foo.glslp", parsed.get("frontend", "shader_preset"))
    }

    @Test fun `skips sections with no entries`() {
        val file = File(tempFolder.root, "skip.ini")
        IniWriter.write(file, mapOf("kept" to mapOf("a" to "1"), "dropped" to emptyMap()))

        val text = file.readText()
        assertTrue(text.contains("[kept]"))
        assertFalse(text.contains("[dropped]"))
    }

    @Test fun `creates parent directories if missing`() {
        val file = File(tempFolder.root, "nested/dir/out.ini")
        IniWriter.write(file, mapOf("s" to mapOf("k" to "v")))
        assertTrue(file.exists())
    }

    @Test fun `writer-parser round-trip preserves data`() {
        val source = mapOf(
            "frontend" to mapOf(
                "scaling" to "FULLSCREEN",
                "max_ff_speed" to "8",
                "shader_preset" to "preset.glslp"
            ),
            "options" to mapOf(
                "swanstation_GPU_Renderer" to "Software"
            )
        )
        val file = File(tempFolder.root, "rt.ini")
        IniWriter.write(file, source)
        val parsed = IniParser.parse(file)
        assertEquals(source.keys, parsed.sections.keys)
        for ((section, entries) in source) {
            for ((key, value) in entries) {
                assertEquals("$section.$key mismatch", value, parsed.get(section, key))
            }
        }
    }

    @Test fun `mergeWrite creates the file when missing`() {
        val file = File(tempFolder.root, "merge.ini")
        IniWriter.mergeWrite(file, "shortcuts", mapOf("SAVE_STATE" to "96,102"))

        val parsed = IniParser.parse(file)
        assertEquals("96,102", parsed.get("shortcuts", "SAVE_STATE"))
    }

    @Test fun `mergeWrite preserves existing sections and keys outside the target section`() {
        val file = File(tempFolder.root, "preserve.ini")
        IniWriter.write(
            file,
            mapOf(
                "frontend" to mapOf("scaling" to "INTEGER"),
                "shortcuts" to mapOf("OLD_KEY" to "1,2")
            )
        )

        IniWriter.mergeWrite(file, "shortcuts", mapOf("NEW_KEY" to "3,4"))

        val parsed = IniParser.parse(file)
        assertEquals("INTEGER", parsed.get("frontend", "scaling"))
        assertEquals("1,2", parsed.get("shortcuts", "OLD_KEY"))
        assertEquals("3,4", parsed.get("shortcuts", "NEW_KEY"))
    }

    @Test fun `mergeWrite overwrites existing keys in the target section`() {
        val file = File(tempFolder.root, "overwrite.ini")
        IniWriter.write(file, mapOf("shortcuts" to mapOf("KEY" to "old")))

        IniWriter.mergeWrite(file, "shortcuts", mapOf("KEY" to "new"))

        assertEquals("new", IniParser.parse(file).get("shortcuts", "KEY"))
    }

    @Test fun `temp file is cleaned up after a successful write`() {
        val file = File(tempFolder.root, "clean.ini")
        IniWriter.write(file, mapOf("s" to mapOf("k" to "v")))

        val tmp = File(file.parentFile, "${file.name}.tmp")
        assertFalse("temp file should not remain after successful write", tmp.exists())
    }

    @Test fun `output is platform-line-ending agnostic when re-parsed`() {
        // We don't care which line-ending IniWriter emits; we care that it round-trips.
        val file = File(tempFolder.root, "endings.ini")
        IniWriter.write(file, mapOf("s" to mapOf("k1" to "v1", "k2" to "v2")))
        val parsed = IniParser.parse(file)
        assertEquals("v1", parsed.get("s", "k1"))
        assertEquals("v2", parsed.get("s", "k2"))
    }

    @Test fun `multiple sections are emitted with section headers`() {
        val file = File(tempFolder.root, "multi.ini")
        IniWriter.write(
            file,
            mapOf(
                "a" to mapOf("x" to "1"),
                "b" to mapOf("y" to "2")
            )
        )
        val text = file.readText()
        assertTrue(text.contains("[a]"))
        assertTrue(text.contains("[b]"))
        assertTrue(text.contains("x=1"))
        assertTrue(text.contains("y=2"))
    }

    @Test fun `mergeWrite into nonexistent file with empty target section yields a written file`() {
        val file = File(tempFolder.root, "empty.ini")
        IniWriter.mergeWrite(file, "section", emptyMap())
        // Empty section is skipped on write, so the resulting file may have no
        // section header but still exist as zero-length output.
        assertTrue(file.exists())
        val parsed = IniParser.parse(file)
        assertNull(parsed.get("section", "anything"))
    }
}
