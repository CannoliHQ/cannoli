package dev.cannoli.scorza.config

import java.io.File

/**
 * Moves a pre-Internal config tree onto the current layout: the top of Config/ is what a user may
 * edit, and Config/Internal holds what Cannoli owns.
 *
 * Every move is decided from the filesystem alone, never from a "done" flag. A run that dies
 * halfway leaves each entry either at its old path or its new one, and the next boot moves
 * whatever is still behind. That matters more than speed here: the tree holds the user's library.
 */
object ConfigLayoutMigration {

    private var migratedRoot: String? = null

    /**
     * Idempotent per root, cheap after the first call, so a caller that must not be beaten to the
     * filesystem can gate on it directly. The databases do: storage-dependent startup runs before
     * boot's migration call, and moving cannoli.db with its -wal out from under an open SQLite
     * connection is how a library gets lost.
     */
    @Synchronized
    fun runOnce(root: File) {
        val key = root.path
        if (migratedRoot == key) return
        run(root)
        migratedRoot = key
    }

    fun run(root: File): Int {
        val paths = CannoliPaths(root)
        if (!paths.configDir.isDirectory) return 0
        return moves(paths).count { (from, to) -> move(from, to) }
    }

    // Old path to new. Anything absent is skipped, so this doubles as the list of what a fresh
    // install simply never has.
    private fun moves(paths: CannoliPaths): List<Pair<File, File>> {
        val config = paths.configDir
        return buildList {
            // SQLite keeps its -wal and -shm beside the database. Moving one without the others
            // strands committed transactions still sitting in the log, so the set travels together.
            for (name in listOf("cannoli.db", "romm.db")) {
                for (suffix in listOf("", "-wal", "-shm")) {
                    add(File(config, "$name$suffix") to File(paths.configInternal, "$name$suffix"))
                }
            }
            add(File(config, "Cache") to paths.configCache)
            add(File(config, "State") to paths.configState)
            add(File(config, "Assets") to paths.configAssets)
            add(File(config, "RetroAchievements") to paths.configRetroAchievements)

            // custom.cfg is the user's, so it leaves RetroArch/ before the rest of it moves inward.
            add(File(File(config, "RetroArch"), "custom.cfg") to paths.customCfg)
            add(File(config, "RetroArch") to paths.configRetroArch)

            add(File(File(config, "Overrides"), "global.ini") to paths.shortcutsIni)

            add(File(config, "arcade_map.txt") to paths.arcadeMapFile)
            add(File(config, "ignore_extensions_roms.txt") to paths.ignoreExtensionsRoms)
            add(File(config, "ignore_files_roms.txt") to paths.ignoreFilesRoms)
        }
    }

    private fun move(from: File, to: File): Boolean {
        if (!from.exists()) return false
        // Already migrated, or the user wrote their own: never clobber the destination.
        if (to.exists()) return false
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return true
        // A rename across mount points fails. Copy instead, and only drop the source once the
        // copy is actually on disk.
        return runCatching {
            if (from.isDirectory) {
                from.copyRecursively(to, overwrite = false)
                from.deleteRecursively()
            } else {
                from.copyTo(to, overwrite = false)
                from.delete()
            }
            true
        }.getOrDefault(false)
    }
}
