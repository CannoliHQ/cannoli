package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.saves.SaveMigration
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

class LocalSaveResolver(private val cannoliRoot: () -> File) {

    constructor(cannoliRoot: File) : this({ cannoliRoot })

    private val paths get() = CannoliPaths(cannoliRoot())
    private val migration get() = SaveMigration(paths)

    private fun savesDir(tag: String) = paths.savesFor(tag)
    private fun gameDir(tag: String, base: String) = paths.saveDirFor(tag, base)

    /**
     * The game's own folder wins over loose files. A folder is only ever there because Cannoli
     * migrated it, a core wrote one, or a sync restored one, so it is the deliberate shape; loose
     * files beside it are what a core left behind before the move.
     */
    private fun sourceRoot(tag: String, base: String): File =
        gameDir(tag, base).takeIf { it.isDirectory && filesUnder(it).isNotEmpty() } ?: savesDir(tag)

    private fun matchingFiles(tag: String, base: String): List<File> {
        val root = sourceRoot(tag, base)
        return if (root == savesDir(tag)) looseFiles(root, base) else filesUnder(root)
    }

    private fun looseFiles(dir: File, base: String): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && (it.nameWithoutExtension == base || it.name.startsWith("$base.")) }
            .sortedBy { it.name }
    }

    private fun filesUnder(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.absolutePath }.toList()

    /**
     * Keyed relative to the directory the files were collected from, which is what keeps a hash
     * stable across the migration: a loose file's path relative to the platform directory and the
     * same file's path relative to its new game folder are both just its name.
     */
    private fun hashKeys(root: File, files: List<File>): Map<String, File> =
        files.associateBy { it.relativeTo(root).invariantSeparatorsPath }

    fun resolve(tag: String, base: String): LocalSave? {
        val root = sourceRoot(tag, base)
        val files = matchingFiles(tag, base)
        if (files.isEmpty()) return null
        val isBundle = files.size > 1
        val hash = if (isBundle) SaveHasher.hashBundle(hashKeys(root, files)) else SaveHasher.hashFile(files.single())
        return LocalSave(
            files = files,
            isBundle = isBundle,
            sizeBytes = files.sumOf { it.length() },
            modifiedMillis = files.maxOf { it.lastModified() },
            contentHash = hash,
            // A lone save uploads as the bare file and is never zipped: Argosy copies a downloaded
            // file straight to the save path, so a zip would arrive there named .srm. The extension
            // is the one the file actually carries, matching Argosy's own naming: mupen writes
            // .eep, .sra, .fla and .mpk, and calling any of them .srm sends the save back as a file
            // the core will not read.
            uploadFileName = if (isBundle) "$base.zip" else nameFor(base, files.single().extension),
        )
    }

    /**
     * Entries under a folder save are rooted at the folder's own name, matching Argosy's archive
     * layout so one zip is readable by both. Loose files stay flat, since there is no folder to
     * name them after.
     */
    fun bundleToZip(tag: String, base: String, dest: File): File {
        val root = sourceRoot(tag, base)
        val files = matchingFiles(tag, base)
        val rooted = root != savesDir(tag)
        ZipOutputStream(dest.outputStream()).use { zos ->
            for (f in files) {
                val relative = f.relativeTo(root).invariantSeparatorsPath
                zos.putNextEntry(ZipEntry(if (rooted) "$base/$relative" else relative))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return dest
    }

    /**
     * Restores into the game's own folder, keeping the archive's structure. A leading folder named
     * after the game is dropped, because that is the root this writer adds; any other root is a
     * layout the archive is carrying deliberately, such as the save-id folder Argosy names a PSP
     * save after, and flattening it would destroy the shape the emulator reads.
     *
     * The whole tree is staged beside the target and swapped in one rename, so a half-extracted
     * save is never live.
     */
    fun applyDownload(tag: String, base: String, downloaded: File, remoteFileName: String? = null) {
        migration.migrateGame(tag, base)
        val singleName = singleSaveName(tag, base, remoteFileName)
        val target = gameDir(tag, base)
        val platformDir = savesDir(tag).apply { mkdirs() }
        val token = java.util.UUID.randomUUID().toString().take(8)
        val staging = File(platformDir, ".part_$token")
        val retired = File(platformDir, ".old_$token")
        try {
            staging.mkdirs()
            if (isZip(downloaded)) {
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

    private fun nameFor(base: String, extension: String): String =
        if (extension.isEmpty()) base else "$base.$extension"

    /**
     * What to call a save that arrives as bare bytes. The server's own file name knows the format,
     * so it wins; failing that the save already on disk does, since the core that wrote it chose
     * that extension. Only a first download for a game nothing has ever saved falls back to .srm.
     */
    private fun singleSaveName(tag: String, base: String, remoteFileName: String?): String {
        val fromRemote = remoteFileName?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() && !it.equals("zip", ignoreCase = true) }
        if (fromRemote != null) return nameFor(base, fromRemote)
        val existing = matchingFiles(tag, base).singleOrNull()?.extension?.takeIf { it.isNotEmpty() }
        return nameFor(base, existing ?: "srm")
    }

    /** Null for an entry that escapes the staging directory rather than one that lands oddly. */
    private fun stagedFileFor(staging: File, base: String, entryName: String): File? {
        val normalized = entryName.replace('\\', '/').trimStart('/')
        val relative = normalized.removePrefix("$base/").ifEmpty { return null }
        val dest = File(staging, relative)
        val root = staging.canonicalFile.path + File.separator
        return dest.takeIf { it.canonicalFile.path.startsWith(root) }
    }

    private fun isZip(file: File): Boolean = file.inputStream().use { ins ->
        val sig = ByteArray(4)
        ins.read(sig) == 4 && sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte()
    }
}
