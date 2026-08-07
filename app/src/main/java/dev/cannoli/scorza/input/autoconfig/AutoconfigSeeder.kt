package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.util.InputLog
import java.io.File

class AutoconfigSeeder(
    private val source: CfgSource,
    private val targetDir: File,
    private val legacyMappingsDir: File,
    private val versionCode: Int,
) {

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

    private fun isUserFile(file: File): Boolean =
        runCatching { RetroArchCfgParser.parse(file.readText()).cannoliUser }.getOrDefault(false)
}