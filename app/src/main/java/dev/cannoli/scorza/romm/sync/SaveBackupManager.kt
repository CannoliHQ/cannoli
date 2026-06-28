package dev.cannoli.scorza.romm.sync

import java.io.File

class SaveBackupManager(
    private val cannoliRoot: File,
    private val resolver: LocalSaveResolver,
) {
    fun backup(tag: String, base: String, keepCount: Int, stamp: Long) {
        if (keepCount <= 0) return
        if (resolver.resolve(tag, base) == null) return
        val dir = File(cannoliRoot, "Backup/SaveSync/$tag/$base").apply { mkdirs() }
        resolver.bundleToZip(tag, base, File(dir, "$stamp.zip"))
        prune(dir, keepCount)
    }

    private fun prune(dir: File, keepCount: Int) {
        val zips = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.name.removeSuffix(".zip").toLongOrNull() ?: 0L } ?: return
        zips.drop(keepCount).forEach { it.delete() }
    }
}
