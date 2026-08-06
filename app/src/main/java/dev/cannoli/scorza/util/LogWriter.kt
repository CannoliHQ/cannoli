package dev.cannoli.scorza.util

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

interface LogTarget {
    fun append(line: String)
    fun flush()
}

/**
 * Every log line in the app is written by this one background thread. Callers only
 * enqueue, so a stalled SD card can never block the thread that produced the line.
 * Blocking the main thread here caused ANRs: an input.log write from onKeyDown sat
 * in a stat() on a busy card for 7.6 seconds while the key event went unhandled.
 */
object LogWriter {
    const val QUEUE_CAPACITY = 4096

    private const val POST_TIMEOUT_MS = 1000L

    private sealed interface Item
    private class Line(val target: LogTarget, val text: String) : Item
    private class Task(val run: () -> Unit) : Item
    private class Barrier(val latch: CountDownLatch) : Item

    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)
    private val dropped = ConcurrentHashMap<LogTarget, AtomicLong>()

    @Volatile private var worker: Thread? = null

    fun write(target: LogTarget, line: String) {
        start()
        if (queue.offer(Line(target, line))) return
        dropped.computeIfAbsent(target) { AtomicLong() }.incrementAndGet()
    }

    /**
     * Queues work for the writer thread. Used for opening and closing files, which is rare
     * enough to be worth waiting briefly on a full queue rather than dropping.
     */
    fun post(task: () -> Unit) {
        start()
        try {
            queue.offer(Task(task), POST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Blocks the caller until everything queued so far has reached the filesystem, or
     * until [timeoutMs] elapses. The timeout covers waiting for room in a full queue as
     * well as the drain itself, so a stalled card bounds this at [timeoutMs] either way.
     */
    fun flush(timeoutMs: Long): Boolean {
        if (worker == null) return true
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val latch = CountDownLatch(1)
        try {
            if (!queue.offer(Barrier(latch), timeoutMs, TimeUnit.MILLISECONDS)) return false
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            return latch.await(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }

    @Synchronized private fun start() {
        if (worker != null) return
        worker = Thread(::loop, "cannoli-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun loop() {
        val touched = mutableSetOf<LogTarget>()
        while (true) {
            var item: Item? = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }
            while (item != null) {
                when (item) {
                    is Line -> {
                        noteDrops(item.target)
                        runCatching { item.target.append(item.text) }
                        touched.add(item.target)
                    }
                    is Task -> runCatching { item.run() }
                    is Barrier -> {
                        flushAll(touched)
                        item.latch.countDown()
                    }
                }
                item = queue.poll()
            }
            flushAll(touched)
        }
    }

    private fun noteDrops(target: LogTarget) {
        val n = dropped[target]?.getAndSet(0) ?: 0
        if (n > 0) runCatching { target.append("... $n log lines dropped (writer fell behind)\n") }
    }

    private fun flushAll(touched: MutableSet<LogTarget>) {
        for (t in touched) runCatching { t.flush() }
        touched.clear()
    }
}
