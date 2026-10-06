package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.saves.SaveMigration
import dev.cannoli.scorza.saves.SharedSaveRoots
import dev.cannoli.scorza.sigil.GameId
import dev.cannoli.scorza.sigil.SaveUsage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class LocalSave(
    val files: List<File>,
    val isBundle: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val contentHash: String,
    val uploadFileName: String,
)

class LocalSaveResolver(
    private val cannoliRoot: () -> File,
    private val gameIdFor: (tag: String, romBaseName: String) -> GameId? = { _, _ -> null },
) {

    constructor(cannoliRoot: File) : this({ cannoliRoot })

    private val paths get() = CannoliPaths(cannoliRoot())
    private val migration get() = SaveMigration(paths)

    private fun savesDir(tag: String) = paths.savesFor(tag)
    private fun gameDir(tag: String, base: String) = paths.saveDirFor(tag, base)

    private data class SaveUnit(
        val root: File,
        val files: List<File>,
        val entryPrefix: String?,
        val uploadName: String?,
    )

    private fun sharedUnit(tag: String, base: String): SaveUnit? {
        val subdir = SharedSaveRoots.subdirFor(tag) ?: return null
        val id = gameIdFor(tag, base) ?: return null
        val key = id.saveId.ifEmpty { id.titleId }.takeIf { it.isNotEmpty() } ?: return null
        val root = File(savesDir(tag), subdir)
        if (!root.isDirectory) return null
        val owned = root.listFiles().orEmpty().filter { it.isDirectory && ownsFolder(it.name, key, id.usage) }
        return SaveUnit(root, owned.flatMap { filesUnder(it) }, entryPrefix = null, uploadName = "$base [$key].zip")
    }

    private fun ownsFolder(name: String, key: String, usage: SaveUsage): Boolean = when (usage) {
        SaveUsage.FOLDER_PREFIX, SaveUsage.FILE_PREFIX -> name.startsWith(key)
        else -> name == key
    }

    private fun unitFor(tag: String, base: String): SaveUnit {
        sharedUnit(tag, base)?.let { return it }
        val dir = gameDir(tag, base)
        if (dir.isDirectory && filesUnder(dir).isNotEmpty()) {
            return SaveUnit(dir, filesUnder(dir), entryPrefix = base, uploadName = null)
        }
        return SaveUnit(savesDir(tag), looseFiles(savesDir(tag), base), entryPrefix = null, uploadName = null)
    }

    private fun looseFiles(dir: File, base: String): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && (it.nameWithoutExtension == base || it.name.startsWith("$base.")) }
            .sortedBy { it.name }
    }

    private fun filesUnder(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.absolutePath }.toList()

    private fun hashKeys(unit: SaveUnit): Map<String, File> =
        unit.files.associateBy { f ->
            val relative = f.relativeTo(unit.root).invariantSeparatorsPath
            unit.entryPrefix?.let { "$it/$relative" } ?: relative
        }

    fun resolve(tag: String, base: String): LocalSave? {
        val unit = unitFor(tag, base)
        val root = unit.root
        val files = unit.files
        if (files.isEmpty()) return null
        val isBundle = files.size > 1
        val hash = if (isBundle) SaveHasher.hashBundle(hashKeys(unit)) else SaveHasher.hashFile(files.single())
        return LocalSave(
            files = files,
            isBundle = isBundle,
            sizeBytes = files.sumOf { it.length() },
            modifiedMillis = files.maxOf { it.lastModified() },
            contentHash = hash,
            uploadFileName = unit.uploadName
                ?: if (isBundle) "$base.zip" else nameFor(base, files.single().extension),
        )
    }

    fun bundleToZip(tag: String, base: String, dest: File): File {
        val unit = unitFor(tag, base)
        ZipOutputStream(dest.outputStream()).use { zos ->
            for (f in unit.files) {
                val relative = f.relativeTo(unit.root).invariantSeparatorsPath
                zos.putNextEntry(ZipEntry(unit.entryPrefix?.let { "$it/$relative" } ?: relative))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return dest
    }

    fun applyDownload(
        tag: String,
        base: String,
        downloaded: File,
        remoteFileName: String? = null,
    ) {
        sharedUnit(tag, base)?.let { unit ->
            applyIntoSharedRoot(tag, downloaded, unit, gameIdFor(tag, base)!!)
            return
        }
        migration.migrateGame(tag, base)
        val singleName = singleSaveName(tag, base, remoteFileName)
        val target = gameDir(tag, base)
        val platformDir = savesDir(tag).apply { mkdirs() }
        val token = java.util.UUID.randomUUID().toString().take(8)
        val staging = File(platformDir, ".part_$token")
        val retired = File(platformDir, ".old_$token")
        try {
            staging.mkdirs()
            if (SaveHasher.isZip(downloaded)) {
                ZipFile(downloaded).use { zf ->
                    for (entry in zf.entries()) {
                        if (entry.isDirectory) continue
                        val dest = stagedFileFor(staging, base, entry.name) ?: continue
                        dest.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                    }
                }
            } else {
                downloaded.copyTo(File(staging, singleName), overwrite = true)
            }
            if (staging.listFiles().isNullOrEmpty()) return
            val hadTarget = target.isDirectory && target.renameTo(retired)
            if (target.exists() && !hadTarget) throw java.io.IOException("could not retire ${target.name}")
            if (!staging.renameTo(target)) {
                if (hadTarget) retired.renameTo(target)
                throw java.io.IOException("could not publish ${target.name}")
            }
        } finally {
            staging.deleteRecursively()
            retired.deleteRecursively()
        }
    }

    private fun applyIntoSharedRoot(tag: String, downloaded: File, unit: SaveUnit, id: GameId) {
        val platformDir = savesDir(tag).apply { mkdirs() }
        val token = java.util.UUID.randomUUID().toString().take(8)
        val staging = File(platformDir, ".part_$token")
        val retired = File(platformDir, ".old_$token")
        try {
            staging.mkdirs()
            if (!SaveHasher.isZip(downloaded)) return
            ZipFile(downloaded).use { zf ->
                for (entry in zf.entries()) {
                    if (entry.isDirectory) continue
                    val dest = stagedFileFor(staging, null, entry.name) ?: continue
                    dest.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                }
            }
            val incoming = staging.listFiles().orEmpty().filter { it.isDirectory }
            if (incoming.isEmpty()) return

            val key = id.saveId.ifEmpty { id.titleId }
            val owned = unit.root.listFiles().orEmpty().filter { it.isDirectory && ownsFolder(it.name, key, id.usage) }
            retired.mkdirs()
            owned.forEach { it.renameTo(File(retired, it.name)) }
            unit.root.mkdirs()
            for (folder in incoming) {
                if (!folder.renameTo(File(unit.root, folder.name))) {
                    retired.listFiles().orEmpty().forEach { it.renameTo(File(unit.root, it.name)) }
                    throw java.io.IOException("could not publish ${folder.name}")
                }
            }
        } finally {
            staging.deleteRecursively()
            retired.deleteRecursively()
        }
    }

    private fun nameFor(base: String, extension: String): String =
        if (extension.isEmpty()) base else "$base.$extension"

    private fun singleSaveName(tag: String, base: String, remoteFileName: String?): String {
        val fromRemote = remoteFileName?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() && !it.equals("zip", ignoreCase = true) }
        if (fromRemote != null) return nameFor(base, fromRemote)
        val existing = unitFor(tag, base).files.singleOrNull()?.extension?.takeIf { it.isNotEmpty() }
        return nameFor(base, existing ?: "srm")
    }

    private fun stagedFileFor(staging: File, base: String?, entryName: String): File? {
        val normalized = entryName.replace('\\', '/').trimStart('/')
        val relative = (base?.let { normalized.removePrefix("$it/") } ?: normalized).ifEmpty { return null }
        val dest = File(staging, relative)
        val root = staging.canonicalFile.path + File.separator
        return dest.takeIf { it.canonicalFile.path.startsWith(root) }
    }
}
