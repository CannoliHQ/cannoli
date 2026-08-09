package dev.cannoli.scorza.launcher

import dev.cannoli.core.CheevosSessionKeys
import dev.cannoli.scorza.util.StorageLog
import java.io.File

/**
 * Strips the RetroAchievements session keys from every persisted RetroArch config, once per app
 * version.
 *
 * Old builds wrote whole-config override dumps (Config/RetroArch/<core>/<contentdir>.cfg) and
 * seeded the base retroarch.cfg from an external RetroArch, so a stale account, token or hardcore
 * line can sit on disk under any override tier. RetroArch layers those overrides over the launch
 * config at load, which is how a stale hardcore line re-enabled hardcore against a forced softcore.
 * The launch config injects these keys fresh every launch (LaunchManager.cheevosOverrides); scrubbing
 * every other config makes the launch config the only place they live, so nothing stale can layer
 * back over it. Whole files are kept and every other key and its formatting is preserved; only the
 * five session keys are removed.
 */
class CheevosOverrideMigration(
    private val configRetroArchDir: File,
    private val versionCode: Int,
) {
    fun scrubIfNeeded() {
        runCatching {
            val stamp = File(configRetroArchDir, STAMP_NAME)
            if (stamp.takeIf { it.exists() }?.readText()?.trim() == versionCode.toString()) return@runCatching
            if (configRetroArchDir.isDirectory) {
                configRetroArchDir.walkTopDown()
                    .filter { it.isFile && it.extension == "cfg" }
                    .forEach(::scrubFile)
            }
            configRetroArchDir.mkdirs()
            stamp.writeText(versionCode.toString())
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
        const val STAMP_NAME = ".cheevos_scrub_version"
    }
}
