package dev.cannoli.scorza.db

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.util.ScanLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanScheduler @Inject constructor(
    private val romScanner: RomScanner,
    private val platformConfig: PlatformConfig,
) {
    data class ScanResult(
        val platformTag: String,
        val counts: RomScanner.SyncCounts,
        val silent: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<String>(capacity = Channel.UNLIMITED)

    private val queued = mutableSetOf<String>()
    private val rerunNeeded = mutableSetOf<String>()
    private var runningTag: String? = null
    private val mutex = Any()

    private val _results = MutableSharedFlow<ScanResult>(extraBufferCapacity = 8)
    val results: SharedFlow<ScanResult> = _results.asSharedFlow()

    init {
        scope.launch { worker() }
    }

    fun runNow(platformTag: String): RomScanner.SyncCounts {
        val tag = platformTag.uppercase()
        synchronized(mutex) {
            while (runningTag == tag || tag in queued) {
                (mutex as java.lang.Object).wait()
            }
            runningTag = tag
        }
        val counts = try {
            romScanner.scanPlatform(tag, isArcade = platformConfig.isArcade(tag))
        } catch (t: Throwable) {
            ScanLog.write("ScanScheduler runNow $tag failed: ${t.message}")
            RomScanner.SyncCounts(0, 0, 0)
        }
        val needsRerun = synchronized(mutex) {
            runningTag = null
            val rerun = rerunNeeded.remove(tag)
            (mutex as java.lang.Object).notifyAll()
            rerun
        }
        val silent = romScanner.consumeLauncherMutation(tag)
        if (counts.inserted + counts.updated + counts.removed > 0) {
            scope.launch { _results.tryEmit(ScanResult(tag, counts, silent)) }
        }
        if (needsRerun) enqueue(tag)
        return counts
    }

    fun enqueue(platformTag: String) {
        val tag = platformTag.uppercase()
        synchronized(mutex) {
            if (runningTag == tag) {
                rerunNeeded.add(tag)
                return
            }
            if (!queued.add(tag)) return
        }
        requests.trySend(tag)
    }

    private suspend fun worker() {
        for (tag in requests) {
            synchronized(mutex) {
                queued.remove(tag)
                runningTag = tag
            }
            val counts = try {
                romScanner.scanPlatform(tag, isArcade = platformConfig.isArcade(tag))
            } catch (t: Throwable) {
                ScanLog.write("ScanScheduler $tag failed: ${t.message}")
                RomScanner.SyncCounts(0, 0, 0)
            }
            val needsRerun = synchronized(mutex) {
                runningTag = null
                val rerun = rerunNeeded.remove(tag)
                (mutex as java.lang.Object).notifyAll()
                rerun
            }
            val silent = romScanner.consumeLauncherMutation(tag)
            if (counts.inserted + counts.updated + counts.removed > 0) {
                _results.tryEmit(ScanResult(tag, counts, silent))
            }
            if (needsRerun) enqueue(tag)
        }
    }
}
