package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.romm.RommDownloadCancelled
import dev.cannoli.scorza.romm.RommHttp
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.ScanLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File

class RommDownloader(
    val queue: RommDownloadQueue,
    private val client: RommClient,
    private val installer: RommInstaller,
    private val links: RommLinkRepository,
    private val artwork: ArtworkLookup,
    private val artDownloader: dev.cannoli.scorza.romm.art.RommArtDownloader,
    private val scanScheduler: ScanScheduler,
    private val store: RommConnectionStore,
    private val http: RommHttp,
    private val paths: CannoliPathsProvider,
    private val concurrency: () -> Int,
    private val scope: CoroutineScope,
) {
    private val workers = mutableSetOf<Job>()
    private val cancelled = mutableSetOf<String>()

    /** True while there is queued or in-flight work. */
    fun hasWork(): Boolean = queue.activeCount() > 0

    @Synchronized
    fun enqueue(items: List<RommDownloadItem>) {
        queue.enqueue(items)
        ensureWorkers()
    }

    fun cancel(key: String) {
        if (isDownloading(key)) synchronized(cancelled) { cancelled.add(key) }
        queue.cancel(key)
    }
    fun cancelAll() {
        queue.state.value.forEach { if (it.status is DownloadStatus.Downloading) synchronized(cancelled) { cancelled.add(it.key) } }
        queue.cancelAll()
    }

    @Synchronized
    fun retry(key: String) { queue.retry(key); ensureWorkers() }

    /** Drops Done/Failed entries; queued and in-flight downloads keep running. */
    fun clearFinished() = queue.clearFinished()

    private fun isDownloading(key: String): Boolean =
        queue.state.value.any { it.key == key && it.status is DownloadStatus.Downloading }

    @Synchronized
    private fun ensureWorkers() {
        workers.removeAll { !it.isActive }
        val want = concurrency().coerceIn(1, MAX_CONCURRENCY)
        while (workers.size < want) {
            lateinit var job: Job
            job = scope.launch(Dispatchers.IO) {
                workerLoop()
                synchronized(this@RommDownloader) { workers.remove(job) }
            }
            workers.add(job)
        }
    }

    private fun workerLoop() {
        while (true) {
            val item = queue.claimNext() ?: break
            synchronized(cancelled) { cancelled.remove(item.key) }
            runItem(item)
        }
    }

    private fun runItem(item: RommDownloadItem) {
        when (item.kind) {
            RommDownloadKind.ROM -> runRom(item)
            RommDownloadKind.MANUAL -> runManual(item)
            RommDownloadKind.FIRMWARE -> runFirmware(item)
        }
    }

    private fun runRom(item: RommDownloadItem) {
        val game = item.game ?: return
        val tempDir = File(paths.root, "Config/Cache/RommDownloads").apply { mkdirs() }
        val temp = File(tempDir, "${item.rommId}.part")
        val fileName = if (installer.isMultiPart(game)) "${game.name}.zip" else game.fsName
        try {
            queue.setStatus(item.key, DownloadStatus.Downloading(0, game.sizeBytes))
            client.downloadRom(
                romId = item.rommId,
                fileName = fileName,
                dest = temp,
                isCancelled = { synchronized(cancelled) { item.key in cancelled } },
                expectedTotal = game.sizeBytes,
            ) { downloaded, total -> queue.setStatus(item.key, DownloadStatus.Downloading(downloaded, total)) }

            scanScheduler.markLauncherMutation(item.tag)
            val result = installer.install(game, item.tag, temp, paths.romDir)
            artDownloader.download(store.host, game.coverPath, item.tag, result.artBaseName)
            links.upsertLink(item.rommId, result.linkRelativePath, "download")
            artwork.invalidate(item.tag)
            scanScheduler.runNow(item.tag)
            queue.setStatus(item.key, DownloadStatus.Done)
        } catch (e: RommDownloadCancelled) {
            temp.delete()
            queue.cancel(item.key)
        } catch (e: Exception) {
            temp.delete()
            ScanLog.write("ERROR romm download ${item.rommId} failed: ${e.message}")
            queue.setStatus(item.key, DownloadStatus.Failed(e.message ?: "failed"))
        } finally {
            synchronized(cancelled) { cancelled.remove(item.key) }
        }
    }

    private fun runManual(item: RommDownloadItem) {
        val game = item.game ?: return
        val url = game.ssMedia?.manual
        if (url == null) {
            queue.setStatus(item.key, DownloadStatus.Failed("no manual"))
            return
        }
        val dir = CannoliPaths(paths.root).guideDir(item.tag, game.name).apply { mkdirs() }
        val dest = File(dir, "Manual.pdf")
        val temp = File(dir, "Manual.pdf.part")
        val isCancelled = { synchronized(cancelled) { item.key in cancelled } }
        try {
            queue.setStatus(item.key, DownloadStatus.Downloading(0, 0))
            if (isCancelled()) { temp.delete(); queue.cancel(item.key); return }
            http.client().newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val total = (resp.body?.contentLength() ?: -1L).coerceAtLeast(0L)
                queue.setStatus(item.key, DownloadStatus.Downloading(0, total))
                temp.outputStream().use { out ->
                    val body = resp.body?.byteStream() ?: throw Exception("empty body")
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        if (isCancelled()) throw RommDownloadCancelled()
                        val read = body.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                        downloaded += read
                        queue.setStatus(item.key, DownloadStatus.Downloading(downloaded, total))
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) { temp.copyTo(dest, overwrite = true); temp.delete() }
            queue.setStatus(item.key, DownloadStatus.Done)
        } catch (e: RommDownloadCancelled) {
            temp.delete()
            queue.cancel(item.key)
        } catch (e: Exception) {
            temp.delete()
            ScanLog.write("ERROR romm manual ${item.rommId} failed: ${e.message}")
            queue.setStatus(item.key, DownloadStatus.Failed(e.message ?: "failed"))
        } finally {
            synchronized(cancelled) { cancelled.remove(item.key) }
        }
    }

    private fun runFirmware(item: RommDownloadItem) {
        val fw = item.firmware ?: return
        val biosDir = CannoliPaths(paths.root).biosFor(item.tag).apply { mkdirs() }
        val safeName = File(fw.fileName).name
        val dest = File(biosDir, safeName)
        if (!dest.canonicalPath.startsWith(biosDir.canonicalPath)) {
            ScanLog.write("ERROR romm firmware ${fw.id} blocked: path traversal in fileName")
            queue.setStatus(item.key, DownloadStatus.Failed("invalid firmware filename"))
            return
        }
        val temp = File(biosDir, "$safeName.part")
        try {
            queue.setStatus(item.key, DownloadStatus.Downloading(0, fw.sizeBytes))
            client.downloadFirmware(
                firmwareId = fw.id,
                fileName = fw.fileName,
                dest = temp,
                isCancelled = { synchronized(cancelled) { item.key in cancelled } },
                expectedTotal = fw.sizeBytes,
            ) { downloaded, total -> queue.setStatus(item.key, DownloadStatus.Downloading(downloaded, total)) }
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) { temp.copyTo(dest, overwrite = true); temp.delete() }
            queue.setStatus(item.key, DownloadStatus.Done)
        } catch (e: RommDownloadCancelled) {
            temp.delete()
            queue.cancel(item.key)
        } catch (e: Exception) {
            temp.delete()
            ScanLog.write("ERROR romm firmware ${fw.id} failed: ${e.message}")
            queue.setStatus(item.key, DownloadStatus.Failed(e.message ?: "failed"))
        } finally {
            synchronized(cancelled) { cancelled.remove(item.key) }
        }
    }

    companion object {
        const val MAX_CONCURRENCY = 4
    }
}
