package dev.cannoli.scorza.db

import dev.cannoli.scorza.config.PlatformConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ScanSchedulerTest {

    private fun newPlatformConfig(): PlatformConfig {
        val cfg = mockk<PlatformConfig>(relaxed = true)
        every { cfg.isArcade(any()) } returns false
        return cfg
    }

    // SharedFlow drops what it emits before anyone subscribes, so the collector has to be
    // registered before the first enqueue rather than given a head start.
    private fun <T> ScanScheduler.subscribe(
        collect: suspend Flow<ScanScheduler.ScanResult>.() -> T,
    ): Deferred<T> {
        val subscribed = CountDownLatch(1)
        val deferred = GlobalScope.async { results.onSubscription { subscribed.countDown() }.collect() }
        assertTrue(subscribed.await(2, TimeUnit.SECONDS))
        return deferred
    }

    @Test
    fun emits_result_when_diff_non_empty() = runBlocking {
        val scanner = mockk<RomScanner>()
        every { scanner.scanPlatform(any(), any()) } returns RomScanner.SyncCounts(1, 0, 0)
        every { scanner.consumeLauncherMutation(any()) } returns false
        val scheduler = ScanScheduler(scanner, newPlatformConfig())

        val deferred = scheduler.subscribe { first() }
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

        val deferred = scheduler.subscribe { first() }
        scheduler.enqueue("NES")
        val result = withTimeout(2000) { deferred.await() }

        assertTrue(result.silent)
    }

    @Test
    fun suppresses_empty_diff() = runBlocking {
        val nesCalls = AtomicInteger(0)
        val scanner = mockk<RomScanner>()
        every { scanner.scanPlatform(any(), any()) } answers {
            if (firstArg<String>() == "NES") {
                nesCalls.incrementAndGet()
                RomScanner.SyncCounts(0, 0, 0)
            } else {
                RomScanner.SyncCounts(1, 0, 0)
            }
        }
        every { scanner.consumeLauncherMutation(any()) } returns false
        val scheduler = ScanScheduler(scanner, newPlatformConfig())

        // GB scans after NES and does emit, so if NES had emitted it would arrive first.
        val deferred = scheduler.subscribe { first() }
        scheduler.enqueue("NES")
        scheduler.enqueue("GB")
        val first = withTimeout(2000) { deferred.await() }

        assertEquals("GB", first.platformTag)
        assertEquals(1, nesCalls.get())
    }

    @Test
    fun coalesces_duplicate_enqueues() {
        val nesCalls = AtomicInteger(0)
        val busyStarted = CountDownLatch(1)
        val releaseBusy = CountDownLatch(1)
        val sentinelScanned = CountDownLatch(1)
        val scanner = mockk<RomScanner>()
        every { scanner.scanPlatform(any(), any()) } answers {
            when (firstArg<String>()) {
                "SNES" -> { busyStarted.countDown(); releaseBusy.await() }
                "NES" -> nesCalls.incrementAndGet()
                "GB" -> sentinelScanned.countDown()
            }
            RomScanner.SyncCounts(0, 0, 0)
        }
        every { scanner.consumeLauncherMutation(any()) } returns false
        val scheduler = ScanScheduler(scanner, newPlatformConfig())

        // Holding the worker on another platform keeps every NES request queued. Otherwise the
        // worker can pick up the first one between enqueues, and the rest then correctly ask for
        // a rerun. GB queues behind NES, so once it scans the NES scan has finished.
        scheduler.enqueue("SNES")
        assertTrue(busyStarted.await(2, TimeUnit.SECONDS))
        repeat(5) { scheduler.enqueue("NES") }
        scheduler.enqueue("GB")
        releaseBusy.countDown()

        assertTrue(sentinelScanned.await(2, TimeUnit.SECONDS))
        assertEquals(1, nesCalls.get())
    }

    @Test
    fun reruns_when_enqueued_during_scan() = runBlocking {
        val calls = AtomicInteger(0)
        val scanner = mockk<RomScanner>()
        val firstScanStarted = CountDownLatch(1)
        val gate = CountDownLatch(1)
        every { scanner.scanPlatform(any(), any()) } answers {
            val n = calls.incrementAndGet()
            if (n == 1) {
                firstScanStarted.countDown()
                gate.await()
            }
            RomScanner.SyncCounts(1, 0, 0)
        }
        every { scanner.consumeLauncherMutation(any()) } returns false
        val scheduler = ScanScheduler(scanner, newPlatformConfig())

        val deferred = scheduler.subscribe { take(2).toList() }
        scheduler.enqueue("NES")
        assertTrue(firstScanStarted.await(2, TimeUnit.SECONDS))
        scheduler.enqueue("NES")
        gate.countDown()

        val received = withTimeout(2000) { deferred.await() }
        assertEquals(2, received.size)
        assertEquals(2, calls.get())
    }
}
