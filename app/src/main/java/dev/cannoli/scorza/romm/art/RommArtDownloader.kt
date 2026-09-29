package dev.cannoli.scorza.romm.art

import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.download.DownloadStaging
import dev.cannoli.scorza.romm.RommArtUrl
import dev.cannoli.scorza.romm.RommHttp
import dev.cannoli.scorza.util.ScanLog
import okhttp3.Request
import dev.cannoli.scorza.util.DirectoryLayout
import java.io.File

class RommArtDownloader(
    private val http: RommHttp,
    private val paths: CannoliPathsProvider,
    private val staging: DownloadStaging,
) {
    /** Downloads [coverPath] for [tag]/[baseName] into the Art dir. Returns true on success. */
    fun download(host: String, coverPath: String?, tag: String, baseName: String): Boolean {
        val url = RommArtUrl.resolve(host, coverPath) ?: return false
        return try {
            http.client().newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val ext = run {
                    val segment = url.substringBefore('?').substringAfterLast('/')
                    val candidate = segment.substringAfterLast('.', "")
                    if (candidate.length in 2..5 && candidate.all { it.isLetterOrDigit() }) candidate else "png"
                }
                val artDir = File(paths.root, "Art/$tag").apply {
                    mkdirs()
                    DirectoryLayout.hideFromGallery(this)
                }
                val dest = File(artDir, "$baseName.$ext")
                val temp = staging.file()
                try {
                    temp.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
                    if (temp.length() == 0L) { staging.discard(temp); return false }
                    staging.commit(temp, dest)
                } catch (e: Exception) {
                    staging.discard(temp)
                    throw e
                }
                true
            }
        } catch (e: Exception) {
            ScanLog.write("romm art download failed for $tag/$baseName: ${e.message}")
            false
        }
    }
}
