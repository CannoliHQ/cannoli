package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.DeviceMapping
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AutoconfigRepository(private val dirProvider: () -> File) {

    private val dir: File get() = dirProvider()

    @Volatile private var cache: List<RetroArchCfgEntry>? = null

    fun listEntries(): List<RetroArchCfgEntry> {
        cache?.let { return it }
        val loaded = dir.listFiles { f -> f.isFile && f.extension.equals("cfg", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching { RetroArchCfgParser.parse(file.readText(), fileName = file.name) }.getOrNull()
            }
            ?: emptyList()
        cache = loaded
        return loaded
    }

    fun findById(id: String): RetroArchCfgEntry? =
        listEntries().firstOrNull { it.fileName == "$id.cfg" }

    fun save(mapping: DeviceMapping) {
        dir.mkdirs()
        val file = File(dir, "${mapping.id}.cfg")
        val tmp = File(dir, "${mapping.id}.cfg.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(RetroArchCfgWriter.write(mapping).toByteArray())
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Failed to rename autoconfig tmp file for ${mapping.id}")
        }
        invalidate()
    }

    fun delete(id: String) {
        File(dir, "$id.cfg").delete()
        invalidate()
    }

    fun invalidate() {
        cache = null
    }
}