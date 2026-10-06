package dev.cannoli.scorza.config

import java.io.File
import java.security.MessageDigest

object ConfigLayoutMigration {

    private val DATABASES = listOf("cannoli.db", "romm.db")
    private val SIDECARS = listOf("", "-wal", "-shm")
    private const val BACKUP_DIR_NAME = "config-pre-layout-migration"

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
        deleteV1InputMappings(paths)
        deleteLegacyShaders(paths.shadersDir)
        val pending = moves(paths).filter { (from, to) -> from.exists() && !to.exists() }
        if (pending.isEmpty()) return 0
        backupDatabases(paths)
        return pending.count { (from, to) -> move(from, to) }
    }

    private fun deleteV1InputMappings(paths: CannoliPaths) {
        val dir = paths.configInputMappings
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isFile && (file.name.endsWith(".ini") || file.name.endsWith(".ini.tmp"))) file.delete()
        }
        dir.delete()
    }

    internal fun deleteLegacyShaders(shadersDir: File, known: Map<String, Set<String>> = LegacyShaders.FILES) {
        for ((folder, entries) in known.entries.groupBy { it.key.substringBefore('/') }) {
            if (!File(shadersDir, folder).isDirectory) continue
            for ((rel, hashes) in entries) {
                val file = File(shadersDir, rel)
                if (file.isFile && sha256(file) in hashes) file.delete()
            }
            entries
                .flatMap { (rel, _) -> rel.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }
                .distinct()
                .sortedByDescending { it.length }
                .forEach { File(shadersDir, it).delete() }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun backupDatabases(paths: CannoliPaths) {
        val dest = File(paths.backupDir, BACKUP_DIR_NAME)
        if (dest.exists()) return
        val sources = DATABASES
            .flatMap { name -> SIDECARS.map { File(paths.configDir, "$name$it") } }
            .filter { it.isFile }
        if (sources.isEmpty()) return
        dest.mkdirs()
        for (file in sources) {
            runCatching { file.copyTo(File(dest, file.name), overwrite = false) }
        }
    }

    private fun moves(paths: CannoliPaths): List<Pair<File, File>> {
        val config = paths.configDir
        return buildList {
            for (name in DATABASES) {
                for (suffix in SIDECARS) {
                    add(File(config, "$name$suffix") to File(paths.configInternal, "$name$suffix"))
                }
            }
            add(File(config, "Cache") to paths.configCache)
            add(File(config, "State") to paths.configState)
            add(File(config, "Assets") to paths.configAssets)
            add(File(config, "RetroAchievements") to paths.configRetroAchievements)

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
        if (to.exists()) return false
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return true
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
