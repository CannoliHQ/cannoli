package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.DeviceMapping
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AutoconfigRepository(
    private val debugBuild: Boolean = false,
    private val dirProvider: () -> File?,
) {

    // Null before first run's storage step. A pad connecting during the controller step recomputes
    // the controller list, which lands here well before anyone has said where Cannoli lives.
    private val dir: File? get() = dirProvider()

    @Volatile private var cache: List<RetroArchCfgEntry>? = null

    // Sorted because consumers pick the first entry that matches a pad and a directory listing has
    // no order of its own: ext4 and exFAT hand back the same cfgs in different sequences, so two
    // equally good matches would otherwise decide a pad's layout by which card it booted from.
    fun listEntries(): List<RetroArchCfgEntry> {
        cache?.let { return it }
        // Deliberately not cached: the root resolves later, and an empty listing must not stick.
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
        listEntries().firstOrNull { it.fileName == "$id.cfg" }

    fun save(mapping: DeviceMapping) {
        // Callers defer the write until storage resolves; MainActivity parks a first-run wizard
        // mapping in memory and saves it once the root is real.
        val dir = dir ?: error("autoconfig save before first run's storage step chose a root")
        dir.mkdirs()
        val file = File(dir, "${mapping.id}.cfg")
        val tmp = File(dir, "${mapping.id}.cfg.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(RetroArchCfgWriter.write(mapping, debugBuild).toByteArray())
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Failed to rename autoconfig tmp file for ${mapping.id}")
        }
        invalidate()
    }

    fun delete(id: String) {
        val dir = dir ?: return
        File(dir, "$id.cfg").delete()
        invalidate()
    }

    fun invalidate() {
        cache = null
    }
}