package dev.cannoli.scorza.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LogWriterTest {

    private val gate = CountDownLatch(1)

    private class Recording : LogTarget {
        val lines = mutableListOf<String>()
        val flushes = AtomicInteger(0)
        @Synchronized override fun append(line: String) { lines.add(line) }
        override fun flush() { flushes.incrementAndGet() }
        @Synchronized fun snapshot(): List<String> = lines.toList()
    }

    /** Stalls the writer thread the way a busy SD card does. */
    private inner class Blocking : LogTarget {
        val lines = mutableListOf<String>()
        @Synchronized override fun append(line: String) {
            gate.await(5, TimeUnit.SECONDS)
            lines.add(line)
        }
        override fun flush() { }
        @Synchronized fun count(): Int = lines.size
        @Synchronized fun snapshot(): List<String> = lines.toList()
    }

    @After fun tearDown() {
        gate.countDown()
        LogWriter.flush(5000)
    }

    @Test fun callerNeverBlocksOnASlowTarget() {
        val slow = Blocking()

        val startedAt = System.nanoTime()
        repeat(20) { LogWriter.write(slow, "line $it\n") }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            "enqueueing 20 lines took ${elapsedMs}ms; callers must not wait on the filesystem",
            elapsedMs < 200
        )

        gate.countDown()
        assertTrue(LogWriter.flush(5000))
        assertEquals(20, slow.count())
    }

    @Test fun flushDeliversEverythingQueued() {
        val target = Recording()
        repeat(50) { LogWriter.write(target, "line $it\n") }

        assertTrue(LogWriter.flush(5000))

        val lines = target.snapshot()
        assertEquals(50, lines.size)
        assertEquals("line 0\n", lines.first())
        assertEquals("line 49\n", lines.last())
        assertTrue(target.flushes.get() > 0)
    }

    @Test fun overflowDropsLinesAndReportsTheCountToTheLogThatOverflowed() {
        val slow = Blocking()
        val overflow = 25
        repeat(LogWriter.QUEUE_CAPACITY + overflow) { LogWriter.write(slow, "line $it\n") }

        gate.countDown()
        assertTrue(LogWriter.flush(10_000))

        // The notice lands on the next line written to the same log, so it reports against
        // the log that actually lost lines rather than whichever one wrote next.
        LogWriter.write(slow, "after\n")
        assertTrue(LogWriter.flush(5000))

        val written = slow.snapshot()
        val notice = written.firstOrNull { it.contains("log lines dropped") }
        assertNotNull("expected a drop notice on the overflowing log", notice)

        // Every submitted line is either written or counted as dropped; none vanish quietly.
        val reportedDrops = Regex("\\.\\.\\. (\\d+) log lines dropped").find(notice!!)!!
            .groupValues[1].toInt()
        val dataLines = written.count { it.startsWith("line ") }
        assertEquals(LogWriter.QUEUE_CAPACITY + overflow, dataLines + reportedDrops)
        assertTrue("expected some drops, got $reportedDrops", reportedDrops > 0)
    }

    @Test fun oneLogOverflowingDoesNotTaintAnother() {
        val slow = Blocking()
        repeat(LogWriter.QUEUE_CAPACITY + 10) { LogWriter.write(slow, "line $it\n") }

        gate.countDown()
        assertTrue(LogWriter.flush(10_000))

        val innocent = Recording()
        LogWriter.write(innocent, "clean\n")
        assertTrue(LogWriter.flush(5000))

        assertEquals(listOf("clean\n"), innocent.snapshot())
    }

    @Test fun postedTasksRunInOrderWithLines() {
        val target = Recording()
        val order = mutableListOf<String>()

        LogWriter.post { synchronized(order) { order.add("task") } }
        LogWriter.write(target, "line\n")
        LogWriter.post { synchronized(order) { order.add("after") } }

        assertTrue(LogWriter.flush(5000))
        assertEquals(listOf("task", "after"), synchronized(order) { order.toList() })
        assertEquals(listOf("line\n"), target.snapshot())
    }
}
