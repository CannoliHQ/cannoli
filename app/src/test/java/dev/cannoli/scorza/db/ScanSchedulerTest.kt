package dev.cannoli.scorza.db

import dev.cannoli.scorza.config.PlatformConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ScanSchedulerTest {

    private fun newPlatformConfig(): PlatformConfig {
        val cfg = mockk<PlatformConfig>(relaxed = true)
        every { cfg.isArcade(any()) } returns false
        return cfg
    }

    @Test
    fun emits_result_when_diff_non_empty() = runBlocking {
        val scanner = mockk<RomScanner>()
        every { scanner.scanPlatform(any(), any()) } returns RomScanner.SyncCounts(1, 0, 0)
        every { scanner.consumeLauncherMutation(any()) } returns false
        val scheduler = ScanScheduler(scanner, newPlatformConfig())

        val deferred = GlobalScope.async { scheduler.results.first() }
        delay(50)
        scheduler.enqueue("NES")
        val result = withTimeout(2000) { deferred.await() }

        assertEquals("NES", result.platformTag)
        assertEquals(1, result.counts.inserted)
        assertFalse(result.silent)
    }

    @Test
    fun marks_result_silent_for_launcher_mutation() = runBlocking {
        val scanner = mockk<RomScanner>()
        every { scanner.scanPlatform(any(), any()) } returns RomScanner.SyncCounts(0, 1, 0)
        every { scanner.consumeLauncherMutation("NES") } returns true
        val scheduler = ScanScheduler(scanner, newPlatformConfig())

        val deferred = GlobalScope.async { scheduler.results.first() }
        delay(50)
        scheduler.enqueue("NES")
        val result = withTimeout(2000) { deferred.await() }

        assertTrue(result.silent)
    }

    @Test
    fun suppresses_empty_diff() = runBlocking {
        val calls = AtomicInteger(0)
        val scanner = mockk<RomScanner>()
        every { scanner.scanPlatform(any(), any()) } answers {
            calls.incrementAndGet()
            RomScanner.SyncCounts(0, 0, 0)
        }
        every { scanner.consumeLauncherMutation(any()) } returns false
        val scheduler = ScanScheduler(scanner, newPlatformConfig())
        val collected = mutableListOf<ScanScheduler.ScanResult>()
        val job = GlobalScope.launch {
            scheduler.results.collect { collected += it }
        }

        scheduler.enqueue("NES")
        delay(200)
        job.cancel()

        assertEquals(1, calls.get())
        assertEquals(0, collected.size)
    }

    @Test
    fun coalesces_duplicate_enqueues() = runBlocking {
        val calls = AtomicInteger(0)
        val scanner = mockk<RomScanner>()
        every { scanner.scanPlatform(any(), any()) } answers {
            calls.incrementAndGet()
            Thread.sleep(50)
            RomScanner.SyncCounts(1, 0, 0)
        }
        every { scanner.consumeLauncherMutation(any()) } returns false
        val scheduler = ScanScheduler(scanner, newPlatformConfig())

        val deferred = GlobalScope.async { scheduler.results.first() }
        delay(20)

        scheduler.enqueue("NES")
        scheduler.enqueue("NES")
        scheduler.enqueue("NES")
        scheduler.enqueue("NES")
        scheduler.enqueue("NES")

        val first = withTimeout(2000) { deferred.await() }
        delay(200)

        assertEquals("NES", first.platformTag)
        assertEquals(1, calls.get())
    }

    @Test
    fun reruns_when_enqueued_during_scan() = runBlocking {
        val calls = AtomicInteger(0)
        val scanner = mockk<RomScanner>()
        val gate = CountDownLatch(1)
        every { scanner.scanPlatform(any(), any()) } answers {
            val n = calls.incrementAndGet()
            if (n == 1) gate.await()
            RomScanner.SyncCounts(1, 0, 0)
        }
        every { scanner.consumeLauncherMutation(any()) } returns false
        val scheduler = ScanScheduler(scanner, newPlatformConfig())

        val deferred = GlobalScope.async { scheduler.results.take(2).toList() }
        delay(50)

        scheduler.enqueue("NES")
        delay(50)
        scheduler.enqueue("NES")
        gate.countDown()

        val received = withTimeout(2000) { deferred.await() }
        assertEquals(2, received.size)
        assertEquals(2, calls.get())
    }
}
