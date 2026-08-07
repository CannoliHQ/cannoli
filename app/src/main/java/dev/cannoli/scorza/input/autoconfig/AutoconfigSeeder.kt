package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.util.InputLog
import java.io.File

class AutoconfigSeeder(
    private val source: CfgSource,
    private val targetDirProvider: () -> File,
    private val legacyMappingsDirProvider: () -> File,
    private val versionCode: Int,
) {

    private val targetDir: File get() = targetDirProvider()
    private val legacyMappingsDir: File get() = legacyMappingsDirProvider()

    fun seedIfNeeded() {
        runCatching {
            val stamp = File(targetDir, ".seed_version")
            if (stamp.takeIf { it.exists() }?.readText()?.trim() == versionCode.toString()) return@runCatching
            targetDir.mkdirs()
            for (name in source.listCfgFiles()) {
                val out = File(targetDir, name.substringAfterLast('/'))
                if (out.exists() && isUserFile(out)) continue
                source.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            legacyMappingsDir.deleteRecursively()
            stamp.writeText(versionCode.toString())
        }.onFailure {
            InputLog.write("[seed] failed: ${it::class.java.simpleName} ${it.message}")
        }
    }

    // Reset restores the bundled cfg instead of only dropping the user's, because an edit is saved
    // over the file it was resolved from and RetroArch reads the same directory.
    fun reseedSingle(fileName: String): Boolean = runCatching {
        val asset = source.listCfgFiles().firstOrNull { it.substringAfterLast('/') == fileName }
            ?: return false
        targetDir.mkdirs()
        source.open(asset).use { input ->
            File(targetDir, fileName).outputStream().use { input.copyTo(it) }
        }
        true
    }.getOrElse {
        InputLog.write("[seed] reseed of $fileName failed: ${it::class.java.simpleName} ${it.message}")
        false
    }

    private fun isUserFile(file: File): Boolean =
        runCatching { RetroArchCfgParser.parse(file.readText()).cannoliUser }.getOrDefault(false)
}