package dev.cannoli.scorza.romm.sync

import java.io.File

data class SaveBackup(val stamp: Long, val file: File, val sizeBytes: Long)
data class SaveBackupGame(val tag: String, val base: String, val displayName: String, val count: Int, val latestStamp: Long)

class SaveBackupManager(
    private val cannoliRoot: File,
    private val resolver: LocalSaveResolver,
) {
    private fun rootDir() = File(cannoliRoot, "Backup/SaveSync")
    private fun gameDir(tag: String, base: String) = File(rootDir(), "$tag/$base")

    fun backup(tag: String, base: String, keepCount: Int, stamp: Long) {
        if (keepCount <= 0) return
        if (resolver.resolve(tag, base) == null) return
        val dir = gameDir(tag, base).apply { mkdirs() }
        resolver.bundleToZip(tag, base, File(dir, "$stamp.zip"))
        prune(dir, keepCount)
    }

    fun list(tag: String, base: String): List<SaveBackup> =
        gameDir(tag, base).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.mapNotNull { f -> f.name.removeSuffix(".zip").toLongOrNull()?.let { SaveBackup(it, f, f.length()) } }
            ?.sortedByDescending { it.stamp } ?: emptyList()

    fun listGames(): List<SaveBackupGame> {
        val root = rootDir()
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<SaveBackupGame>()
        root.listFiles { f -> f.isDirectory }?.forEach { tagDir ->
            tagDir.listFiles { f -> f.isDirectory }?.forEach { baseDir ->
                val backups = list(tagDir.name, baseDir.name)
                if (backups.isNotEmpty()) {
                    val displayName = dev.cannoli.scorza.util.RomNaming.splitNameAndTags(baseDir.name).first
                    out.add(SaveBackupGame(tagDir.name, baseDir.name, displayName, backups.size, backups.first().stamp))
                }
            }
        }
        return out.sortedWith(compareBy({ it.tag }, { it.base }))
    }

    // Restore a chosen backup over the current save. The current save is backed up first (so a
    // wrong restore is reversible), and the source zip is copied out before that backup runs in
    // case pruning would remove it.
    fun restore(tag: String, base: String, stamp: Long, keepCount: Int): Boolean {
        val zip = File(gameDir(tag, base), "$stamp.zip")
        if (!zip.isFile) return false
        val temp = File.createTempFile("restore", ".zip", File(cannoliRoot, "Backup").apply { mkdirs() })
        return try {
            zip.copyTo(temp, overwrite = true)
            backup(tag, base, keepCount, System.currentTimeMillis())
            resolver.applyDownload(tag, base, temp)
            true
        } finally {
            temp.delete()
        }
    }

    private fun prune(dir: File, keepCount: Int) {
        val zips = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.name.removeSuffix(".zip").toLongOrNull() ?: 0L } ?: return
        zips.drop(keepCount).forEach { it.delete() }
    }
}
