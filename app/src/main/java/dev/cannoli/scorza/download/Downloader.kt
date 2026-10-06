package dev.cannoli.scorza.download

import dev.cannoli.scorza.util.RommLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class Downloader(
    private val queue: DownloadQueue,
    handlers: List<DownloadHandler>,
    private val lanes: List<Lane>,
    private val scope: CoroutineScope,
) {
    data class Lane(val kinds: Set<DownloadKind>, val concurrency: () -> Int)

    private val byKind = handlers.associateBy { it.kind }
    private val workers = mutableMapOf<Lane, MutableSet<Job>>()
    private val cancelled = mutableSetOf<String>()

    val state get() = queue.state

    fun hasWork(): Boolean = queue.activeCount() > 0

    fun activeCount(): Int = queue.activeCount()

    fun enqueue(items: List<DownloadItem>) {
        queue.enqueue(items)
        ensureWorkers()
    }

    fun cancel(key: String) {
        synchronized(cancelled) { cancelled.add(key) }
        queue.cancel(key)
    }

    fun cancelAll() {
        synchronized(cancelled) {
            queue.state.value.forEach { cancelled.add(it.key) }
        }
        queue.cancelAll()
    }

    fun retry(key: String) { queue.retry(key); ensureWorkers() }

    fun clearFinished() = queue.clearFinished()

    private fun isCancelled(key: String): Boolean = synchronized(cancelled) { key in cancelled }

    private fun ensureWorkers() {
        synchronized(workers) {
            for (lane in lanes) {
                val running = workers.getOrPut(lane) { mutableSetOf() }
                running.removeAll { it.isCompleted }
                if (queue.activeCount(lane.kinds) == 0) continue
                val want = lane.concurrency().coerceAtLeast(1)
                repeat((want - running.size).coerceAtLeast(0)) {
                    running.add(scope.launch(Dispatchers.IO) { workerLoop(lane) })
                }
            }
        }
    }

    private fun workerLoop(lane: Lane) {
        while (true) {
            val item = queue.claimNext(lane.kinds) ?: break
            synchronized(cancelled) { cancelled.remove(item.key) }
            runItem(item)
            synchronized(cancelled) { cancelled.remove(item.key) }
        }
    }

    private fun runItem(item: DownloadItem) {
        val handler = byKind[item.kind]
        if (handler == null) {
            queue.setStatus(item.key, DownloadStatus.Failed("no handler for ${item.kind}"))
            return
        }
        try {
            queue.setStatus(item.key, DownloadStatus.Downloading(0, item.sizeBytes))
            handler.run(
                item,
                onProgress = { done, total -> queue.setStatus(item.key, DownloadStatus.Downloading(done, total)) },
                isCancelled = { isCancelled(item.key) },
            )
            queue.setStatus(item.key, DownloadStatus.Done)
        } catch (_: DownloadCancelled) {
            queue.cancel(item.key)
        } catch (t: Throwable) {
            if (isCancelled(item.key)) { queue.cancel(item.key); return }
            RommLog.write("download failed for ${item.key}: ${t.message}")
            queue.setStatus(item.key, DownloadStatus.Failed(t.message ?: t.javaClass.simpleName))
        }
    }

    companion object {
        const val MAX_CONCURRENCY = 4
    }
}
