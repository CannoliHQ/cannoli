package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.romm.RommDownloadCancelled
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommHttp
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.RommLog
import okhttp3.Request
import java.io.File
import dev.cannoli.scorza.download.DownloadCancelled
import dev.cannoli.scorza.download.DownloadHandler
import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.download.DownloadKind

/** What a RomM transfer needs that the queue has no business knowing. */
data class RommPayload(
    val rommId: Int,
    val game: RommGame? = null,
    val firmware: dev.cannoli.scorza.romm.RommFirmware? = null,
)

/**
 * The RomM half of the download queue: roms, their manuals and firmware. Scheduling, progress,
 * cancellation and the terminal state belong to the queue now, so these bodies only fetch, install
 * and clean up after themselves.
 */
class RommDownloadHandler(
    override val kind: DownloadKind,
    private val client: RommClient,
    private val installer: RommInstaller,
    private val links: RommLinkRepository,
    private val artwork: ArtworkLookup,
    private val artDownloader: dev.cannoli.scorza.romm.art.RommArtDownloader,
    private val scanScheduler: ScanScheduler,
    private val store: RommConnectionStore,
    private val http: RommHttp,
    private val paths: CannoliPathsProvider,
) : DownloadHandler {

    override fun run(
        item: DownloadItem,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val p = item.payload as? RommPayload ?: throw Exception("not a RomM item")
        when (kind) {
            DownloadKind.ROM -> runRom(item, p, onProgress, isCancelled)
            DownloadKind.MANUAL -> runManual(item, p, onProgress, isCancelled)
            DownloadKind.FIRMWARE -> runFirmware(item, p, onProgress, isCancelled)
            else -> throw Exception("unsupported kind $kind")
        }
    }

    private fun runRom(item: DownloadItem, p: RommPayload, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) {
        val game = p.game ?: return
        val tempDir = File(paths.root, "Config/Cache/RommDownloads").apply { mkdirs() }
        val multiPart = installer.isMultiPart(game)
        val source = if (multiPart) File(tempDir, "${p.rommId}.parts") else File(tempDir, "${p.rommId}.part")
        try {
            onProgress(0, game.sizeBytes)
            if (multiPart) downloadParts(item, p, game, source, onProgress, isCancelled) else {
                client.downloadRom(
                    romId = p.rommId,
                    fileName = game.fsName,
                    dest = source,
                    isCancelled = isCancelled,
                    expectedTotal = game.sizeBytes,
                ) { downloaded, total -> onProgress(downloaded, total) }
            }

            scanScheduler.markLauncherMutation(item.tag)
            val result = installer.install(game, item.tag, source, paths.romDir)
            runCatching {
                adoptGuideDir(
                    CannoliPaths(paths.root).guidesFor(item.tag),
                    guideBaseName(null, game.fsName),
                    guideBaseName(result.linkRelativePath, game.fsName),
                )
            }.onFailure { RommLog.write("ERROR romm guide adopt ${p.rommId} failed: ${it.message}") }
            artDownloader.download(store.host, game.coverPath, item.tag, result.artBaseName)
            links.upsertLink(p.rommId, result.linkRelativePath, "download")
            artwork.invalidate(item.tag)
            scanScheduler.runNow(item.tag)
        } catch (e: RommDownloadCancelled) {
            deleteSource(source)
            throw DownloadCancelled()
        } catch (e: Exception) {
            deleteSource(source)
            RommLog.write("ERROR romm download ${p.rommId} failed: ${e.message}")
            throw e
        }
    }

    private fun downloadParts(item: DownloadItem, p: RommPayload, game: RommGame, staging: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) {
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        var completed = 0L
        for (file in game.files.sortedWith(compareBy({ it.subDir }, { it.fileName }))) {
            val dest = File(if (file.subDir.isEmpty()) staging else File(staging, file.subDir), File(file.fileName).name)
            if (!dest.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                throw Exception("invalid file path for ${file.fileName}")
            }
            client.downloadRomFile(
                romId = p.rommId,
                fileId = file.id,
                fileName = File(file.fileName).name,
                dest = dest,
                isCancelled = isCancelled,
                expectedTotal = file.sizeBytes,
            ) { downloaded, _ -> onProgress(completed + downloaded, game.sizeBytes) }
            completed += dest.length()
        }
    }

    private fun deleteSource(source: File) {
        if (source.isDirectory) source.deleteRecursively() else source.delete()
    }

    private fun runManual(item: DownloadItem, p: RommPayload, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) {
        val game = p.game ?: return
        val url = RommManual.sourceUrl(store.host, game)
        if (url == null) {
            throw Exception("no manual")
        }
        val base = guideBaseName(links.relativePathFor(p.rommId), game.fsName)
        val dir = CannoliPaths(paths.root).guideDir(item.tag, base).apply { mkdirs() }
        val dest = File(dir, "Manual.pdf")
        val temp = File(dir, "Manual.pdf.part")
                try {
            onProgress(0, 0)
            if (isCancelled()) { temp.delete(); throw DownloadCancelled(); return }
            http.downloadClient().newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val total = (resp.body?.contentLength() ?: -1L).coerceAtLeast(0L)
                onProgress(0, total)
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
                        onProgress(downloaded, total)
                    }
                }
                if (!RommManual.looksLikePdf(temp)) {
                    RommLog.write(
                        "ERROR romm manual ${p.rommId} not a pdf: " +
                            "content-type=${resp.header("Content-Type") ?: "(none)"} " +
                            "bytes=${temp.length()} head=${RommManual.describeHead(temp)}"
                    )
                    temp.delete()
                    throw Exception("manual is not a PDF")
                }
            }
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) { temp.copyTo(dest, overwrite = true); temp.delete() }
        } catch (e: RommDownloadCancelled) {
            temp.delete()
            throw DownloadCancelled()
        } catch (e: Exception) {
            temp.delete()
            RommLog.write("ERROR romm manual ${p.rommId} failed: ${e.message}")
            throw e
        }
    }

    private fun runFirmware(item: DownloadItem, p: RommPayload, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) {
        val fw = p.firmware ?: return
        val biosDir = CannoliPaths(paths.root).biosFor(item.tag).apply { mkdirs() }
        val safeName = File(fw.fileName).name
        val dest = File(biosDir, safeName)
        if (!dest.canonicalPath.startsWith(biosDir.canonicalPath)) {
            RommLog.write("ERROR romm firmware ${fw.id} blocked: path traversal in fileName")
            throw Exception("invalid firmware filename")
        }
        val temp = File(biosDir, "$safeName.part")
        try {
            onProgress(0, fw.sizeBytes)
            client.downloadFirmware(
                firmwareId = fw.id,
                fileName = fw.fileName,
                dest = temp,
                isCancelled = isCancelled,
                expectedTotal = fw.sizeBytes,
            ) { downloaded, total -> onProgress(downloaded, total) }
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) { temp.copyTo(dest, overwrite = true); temp.delete() }
        } catch (e: RommDownloadCancelled) {
            temp.delete()
            throw DownloadCancelled()
        } catch (e: Exception) {
            temp.delete()
            RommLog.write("ERROR romm firmware ${fw.id} failed: ${e.message}")
            throw e
        }
    }



}
