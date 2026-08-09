package dev.cannoli.scorza.launcher

import dev.cannoli.core.CheevosSessionKeys
import dev.cannoli.scorza.util.StorageLog
import java.io.File

/**
 * Strips the RetroAchievements session keys from every persisted RetroArch config, every boot.
 *
 * Old builds wrote whole-config override dumps (Config/RetroArch/<core>/<contentdir>.cfg) and
 * seeded the base retroarch.cfg from an external RetroArch, so a stale account, token or hardcore
 * line can sit on disk under any override tier. RetroArch layers those overrides over the launch
 * config at load, which is how a stale hardcore line re-enabled hardcore against a forced softcore.
 * The launch config injects these keys fresh every launch (LaunchManager.cheevosOverrides); scrubbing
 * every other config makes the launch config the only place they live, so nothing stale can layer
 * back over it. Whole files are kept and every other key and its formatting is preserved; only the
 * five session keys are removed.
 *
 * Runs unconditionally rather than once per app version: scrubFile only writes when a file still
 * carries a session key, so a clean tree costs one cheap read-only walk, and an unconditional scrub
 * closes re-pollution vectors a version stamp would miss at the same version (a restored backup, an
 * older side-loaded build, a hand-edit) - including a re-polluted key re-persisting through the
 * native override save, which merges over the existing file.
 */
class CheevosOverrideMigration(
    // Resolved fresh on every call rather than captured at construction, so a root re-resolved
    // after setup (e.g. a portable SD move) never leaves this scrubbing a stale directory.
    private val configRetroArchDir: () -> File,
) {
    fun scrubIfNeeded() {
        runCatching {
            val dir = configRetroArchDir()
            if (dir.isDirectory) {
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
}
