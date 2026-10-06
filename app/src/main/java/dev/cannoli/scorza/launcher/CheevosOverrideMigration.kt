package dev.cannoli.scorza.launcher

import dev.cannoli.core.CheevosSessionKeys
import dev.cannoli.scorza.util.StorageLog
import java.io.File

class CheevosOverrideMigration(
    private val configRetroArchDir: () -> File,
) {
    fun scrubIfNeeded() {
        runCatching {
            val dir = configRetroArchDir()
            if (dir.isDirectory) {
                File(dir, STALE_STAMP_NAME).delete()
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "cfg" }
                    .forEach(::scrubFile)
            }
        }.onFailure {
            StorageLog.write("[cheevos-scrub] failed: ${it::class.java.simpleName} ${it.message}")
        }
    }

    private fun scrubFile(file: File) {
        val original = file.readText()
        // split("\n"), not lines(), so a trailing newline and any \r stay exactly as written.
        val lines = original.split("\n")
        val kept = lines.filterNot { line ->
            line.substringBefore('=').trim() in CheevosSessionKeys.ALL
        }
        if (kept.size == lines.size) return
        file.writeText(kept.joinToString("\n"))
    }

    private companion object {
        const val STALE_STAMP_NAME = ".cheevos_scrub_version"
    }
}
