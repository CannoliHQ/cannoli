package dev.cannoli.scorza.sigil

import dev.cannoli.scorza.db.GameIdRepository
import dev.cannoli.scorza.db.ProbeTarget
import java.io.File

/**
 * Android-free drain: take unprobed roms on the supported tags, read an id out of each, and record
 * the attempt whether or not it produced one. The extractor is injected so the selection, the
 * container guard and the persistence are testable without loading the native library.
 *
 * A rom row does not always point at a disc image. The walker makes an m3u the launch file for a
 * multi-disc set and a cue the launch file for a bin/cue game, so the probe asks it which files
 * make up the game and takes the first one that is not a playlist or a sidecar. That lands on disc
 * one, which is what boots and whose siblings share its saves anyway.
 */
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
                // A row that recorded nothing is a row the next pending() hands straight back, so
                // carrying on would ask the same question forever. Only an unloadable library
                // does that, and it does not become loadable later in the process.
                if (!probeOne(target)) return probed
                probed++
                yieldBetween()
            }
        }
        return probed
    }

    /** True when the attempt was recorded, which is every outcome except an unloadable library. */
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
                // Nothing was read, so nothing is recorded: a build that cannot load the library
                // must not mark the whole library probed and lock itself out of a later one.
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

        /** Playlists, cue sheets and the art and metadata that sit beside a dump. */
        val NON_IMAGE_EXTENSIONS = setOf(
            "m3u", "cue", "ccd", "sub", "sbi", "toc", "gdi",
            "txt", "nfo", "xml", "dat", "sfv", "md5",
            "jpg", "jpeg", "png", "webp", "pdf",
        )
    }
}
