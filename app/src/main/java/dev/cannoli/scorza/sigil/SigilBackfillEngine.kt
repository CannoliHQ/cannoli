package dev.cannoli.scorza.sigil

import dev.cannoli.scorza.db.GameIdRepository
import dev.cannoli.scorza.db.ProbeTarget
import java.io.File

class SigilBackfillEngine(
    private val repository: GameIdRepository,
    private val gameFiles: (File) -> List<File>,
    private val extract: (String, SigilPlatform) -> SigilNative.Result = SigilNative::extract,
    private val log: (String) -> Unit = {},
) {
    suspend fun drain(
        batchSize: Int = DEFAULT_BATCH,
        isActive: () -> Boolean = { true },
        yieldBetween: suspend () -> Unit = {},
    ): Int {
        var probed = 0
        while (isActive()) {
            val batch = repository.pending(SigilPlatform.TAGS, batchSize)
            if (batch.isEmpty()) break
            for (target in batch) {
                if (!isActive()) return probed
                if (!probeOne(target)) return probed
                probed++
                yieldBetween()
            }
        }
        return probed
    }

    fun probeOne(target: ProbeTarget): Boolean {
        try {
            val platform = SigilPlatform.fromTag(target.platformTag)
            val file = platform?.let { probeFile(target.file) }
            if (platform == null || file == null) {
                repository.record(target.romId, fingerprint(target.file), null)
                return true
            }
            when (val result = extract(file.absolutePath, platform)) {
                is SigilNative.Result.Extracted -> repository.record(target.romId, fingerprint(file), result.id)
                is SigilNative.Result.Failed -> {
                    log("sigil: ${file.name} on ${target.platformTag}: ${result.reason}")
                    repository.record(target.romId, fingerprint(file), null)
                }
                SigilNative.Result.Unavailable -> return false
            }
        } catch (t: Throwable) {
            log("sigil: ${target.file.name} threw ${t.javaClass.simpleName}")
            runCatching { repository.record(target.romId, fingerprint(target.file), null) }
        }
        return true
    }

    private fun probeFile(primary: File): File? {
        val candidates = runCatching { gameFiles(primary) }.getOrNull().orEmpty().ifEmpty { listOf(primary) }
        return candidates.firstOrNull { it.isFile && it.extension.lowercase() !in NON_IMAGE_EXTENSIONS }
    }

    private fun fingerprint(file: File): String = "${file.length()}:${file.lastModified()}"

    private companion object {
        const val DEFAULT_BATCH = 32

        val NON_IMAGE_EXTENSIONS = setOf(
            "m3u", "cue", "ccd", "sub", "sbi", "toc", "gdi",
            "txt", "nfo", "xml", "dat", "sfv", "md5",
            "jpg", "jpeg", "png", "webp", "pdf",
        )
    }
}
