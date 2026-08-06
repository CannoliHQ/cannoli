package dev.cannoli.scorza.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LogSinkTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = File.createTempFile("cannoli", "").also { it.delete(); it.mkdirs() }
    }

    @After fun tearDown() {
        LogWriter.flush(5000)
        root.deleteRecursively()
    }

    private fun sink(maxBytes: Long, name: String = "test.log"): Pair<LogSink, File> {
        val file = File(File(root, "Logs"), name)
        return LogSink(maxBytes).also { it.open(file) } to file
    }

    @Test fun createsParentDirectoriesOnFirstLine() {
        val (sink, file) = sink(0)
        LogWriter.write(sink, "hello\n")
        LogWriter.flush(5000)

        assertTrue(file.exists())
        assertEquals("hello\n", file.readText())
    }

    @Test fun doesNotCreateTheFileUntilSomethingIsWritten() {
        val (_, file) = sink(0)
        LogWriter.flush(5000)

        assertFalse(file.exists())
    }

    @Test fun appendsAcrossManyLines() {
        val (sink, file) = sink(0)
        repeat(100) { LogWriter.write(sink, "line $it\n") }
        LogWriter.flush(5000)

        val lines = file.readLines()
        assertEquals(100, lines.size)
        assertEquals("line 99", lines.last())
    }

    @Test fun rotatesOnceItOutgrowsTheLimit() {
        val max = 2048L
        val (sink, file) = sink(max)
        repeat(400) { LogWriter.write(sink, "line $it padded out to make this longer\n") }
        LogWriter.flush(5000)

        assertTrue("file was ${file.length()} bytes, limit is $max", file.length() <= max)
        val text = file.readText()
        assertTrue("rotation must keep the newest lines", text.contains("line 399"))
        assertFalse("rotation must drop the oldest lines", text.contains("line 0 padded"))
    }

    @Test fun rotationKeepsWholeLines() {
        val (sink, file) = sink(1024)
        repeat(300) { LogWriter.write(sink, "line $it\n") }
        LogWriter.flush(5000)

        for (line in file.readLines().filter { it.isNotEmpty() }) {
            assertTrue("truncated mid-line: '$line'", line.startsWith("line "))
        }
    }

    @Test fun writesGoNowhereAfterClose() {
        val (sink, file) = sink(0)
        LogWriter.write(sink, "before\n")
        sink.close()
        LogWriter.write(sink, "after\n")
        LogWriter.flush(5000)

        assertEquals("before\n", file.readText())
        assertFalse(sink.isReady())
    }

    @Test fun reopeningPointsAtTheNewFile() {
        val (sink, first) = sink(0, "first.log")
        LogWriter.write(sink, "one\n")

        val second = File(File(root, "Logs"), "second.log")
        sink.open(second)
        LogWriter.write(sink, "two\n")
        LogWriter.flush(5000)

        assertEquals("one\n", first.readText())
        assertEquals("two\n", second.readText())
    }
}
