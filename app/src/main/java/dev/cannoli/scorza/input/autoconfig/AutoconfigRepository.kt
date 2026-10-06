package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.DeviceMapping
import java.io.File

class AutoconfigRepository(
    private val debugBuild: Boolean = false,
    private val stagingDirProvider: () -> File? = { null },
    private val dirProvider: () -> File?,
) {

    private val dir: File? get() = dirProvider()

    private val stagingDir: File? get() = stagingDirProvider()

    private val activeDir: File? get() = dir ?: stagingDir

    @Volatile private var cache: List<RetroArchCfgEntry>? = null

    fun listEntries(): List<RetroArchCfgEntry> {
        cache?.let { return it }
        val dir = dir ?: return emptyList()
        val loaded = dir.listFiles { f -> f.isFile && f.extension.equals("cfg", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching { RetroArchCfgParser.parse(file.readText(), fileName = file.name) }.getOrNull()
            }
            ?.sortedBy { it.fileName }
            ?: emptyList()
        cache = loaded
        return loaded
    }

    fun findById(id: String): RetroArchCfgEntry? =
        (listEntries() + stagedEntries()).firstOrNull { it.fileName == "$id.cfg" }

    fun stagedEntries(): List<RetroArchCfgEntry> {
        val dir = stagingDir?.takeIf { it.isDirectory } ?: return emptyList()
        return dir.listFiles { f: File -> f.isFile && f.extension.equals("cfg", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching { RetroArchCfgParser.parse(file.readText(), fileName = file.name) }.getOrNull()
            }
            ?.sortedBy { it.fileName }
            ?: emptyList()
    }

    fun save(mapping: DeviceMapping) {
        val dir = activeDir ?: error("autoconfig save with neither a chosen root nor a staging dir")
        dir.mkdirs()
        val file = File(dir, "${mapping.id}.cfg")
        writeCfgAtomic(file, RetroArchCfgWriter.write(mapping, debugBuild))
        invalidate()
    }

    fun delete(id: String) {
        val dir = activeDir ?: return
        File(dir, "$id.cfg").delete()
        invalidate()
    }

    fun promoteStaging() {
        val target = dir ?: return
        val source = stagingDir?.takeIf { it.isDirectory } ?: return
        val staged = source.listFiles { f: File -> f.isFile && f.extension.equals("cfg", true) }
            ?: return
        if (staged.isNotEmpty()) target.mkdirs()
        for (file in staged) {
            runCatching { writeCfgAtomic(File(target, file.name), file.readText()) }
                .onSuccess { file.delete() }
        }
        source.delete()
        invalidate()
    }

    fun invalidate() {
        cache = null
    }
}