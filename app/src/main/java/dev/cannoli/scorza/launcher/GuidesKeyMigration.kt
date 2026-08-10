package dev.cannoli.scorza.launcher

import dev.cannoli.core.IniParser
import dev.cannoli.core.IniWriter
import dev.cannoli.core.RomKey
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.util.StorageLog
import java.io.File

/**
 * One-shot: renames pre-cutover Guides/<tag>/<dir> directories keyed by a rom's display name to
 * the canonical base-name key GuideManager and the launcher guide sites now resolve by (RomKey).
 * Kitchen already uploads under the base name, so this only touches dirs the launcher or IGM
 * created before the cutover.
 *
 * For each Guides/<tag>/<dir>:
 * - the dir already equals a rom's base name: left alone.
 * - the dir equals exactly one rom's display name and differs from its base name: renamed to the
 *   base name, merging into an existing base-name dir and skipping any duplicate filename.
 * - the dir matches multiple roms' display names, or none at all: skipped and logged, since
 *   guessing with user content is worse than leaving a dir the launcher no longer resolves.
 *
 * The `.guides_key_version` stamp is only written after every dir in the pass either fully
 * migrated or was deliberately skipped (ambiguous or unmatched). A dir that failed partway
 * through a rename or merge leaves the pass unstamped, so the whole thing retries on the next
 * boot rather than stranding content the app can no longer resolve - the same retry-until-clean
 * posture as CheevosOverrideMigration, gated on success instead of removing the gate entirely.
 * Every operation only acts on whatever is physically on disk at call time, so a retry of a
 * dir that already fully or partially migrated is safe: an already-base-named or already-merged
 * dir is a cheap no-op, and a partial merge just picks up the files still left behind.
 */
class GuidesKeyMigration(
    // Resolved fresh on every call, like CheevosOverrideMigration, so a root re-resolved after
    // setup (e.g. a portable SD move) never leaves this migrating a stale directory.
    private val guidesDir: () -> File,
    private val positionsFile: () -> File,
    private val roms: () -> List<Rom>,
) {
    fun migrateIfNeeded() {
        runCatching {
            val dir = guidesDir()
            val stamp = File(dir, STAMP_NAME)
            if (stamp.takeIf { it.exists() }?.readText()?.trim() == VERSION.toString()) return@runCatching
            val success = if (dir.isDirectory) migrate(dir) else true
            if (!success) return@runCatching
            dir.mkdirs()
            stamp.writeText(VERSION.toString())
        }.onFailure {
            StorageLog.write("[guides-key-migration] failed: ${it::class.java.simpleName} ${it.message}")
        }
    }

    private fun migrate(guidesRoot: File): Boolean {
        val byTag = roms().groupBy { it.platformTag }
        val tagDirs = guidesRoot.listFiles { f -> f.isDirectory } ?: return false
        var success = true
        for (tagDir in tagDirs) {
            if (!migrateTag(tagDir, byTag[tagDir.name].orEmpty())) success = false
        }
        return success
    }

    private fun migrateTag(tagDir: File, tagRoms: List<Rom>): Boolean {
        val baseNames = tagRoms.mapTo(mutableSetOf()) { RomKey.baseName(it.path) }
        val byDisplayName = tagRoms.groupBy { it.displayName }
        val dirs = tagDir.listFiles { f -> f.isDirectory } ?: return false
        var success = true
        for (dir in dirs) {
            val name = dir.name
            if (name in baseNames) continue
            val matches = byDisplayName[name].orEmpty()
            when {
                matches.size == 1 -> {
                    if (!renameToBaseName(tagDir.name, dir, RomKey.baseName(matches[0].path))) success = false
                }
                // Ambiguous or unmatched dirs are a deliberate, permanent hold, not a failure:
                // they must not block the stamp, or the pass would retry them forever for nothing.
                matches.size > 1 -> StorageLog.write(
                    "[guides-key-migration] skip ${tagDir.name}/$name: matches ${matches.size} roms' display names"
                )
                else -> StorageLog.write("[guides-key-migration] skip ${tagDir.name}/$name: no matching rom")
            }
        }
        return success
    }

    private fun renameToBaseName(tag: String, sourceDir: File, baseName: String): Boolean {
        if (baseName == sourceDir.name) return true
        val targetDir = File(sourceDir.parentFile, baseName)
        if (!targetDir.exists()) {
            if (sourceDir.renameTo(targetDir)) {
                rewritePositionKeys(tag, sourceDir.name, baseName, moved = null)
                StorageLog.write("[guides-key-migration] renamed $tag/${sourceDir.name} -> $tag/$baseName")
                return true
            }
            StorageLog.write("[guides-key-migration] failed to rename $tag/${sourceDir.name} -> $tag/$baseName")
            return false
        }
        return mergeInto(tag, sourceDir, targetDir, baseName)
    }

    // A duplicate filename means the target already holds this guide, so the source's copy is
    // discarded rather than left behind as a dir the app can never resolve again. A file that
    // fails to move is neither moved nor discarded: it stays physically at the old location, so
    // its position key is left alone too (see rewritePositionKeys), and the dir isn't removed.
    private fun mergeInto(tag: String, sourceDir: File, targetDir: File, baseName: String): Boolean {
        val oldName = sourceDir.name
        val moved = mutableSetOf<String>()
        val discarded = mutableSetOf<String>()
        var allHandled = true
        val files = sourceDir.listFiles() ?: return false
        files.filter { it.isFile }.forEach { file ->
            val dest = File(targetDir, file.name)
            when {
                dest.exists() -> if (file.delete()) discarded.add(file.name) else allHandled = false
                file.renameTo(dest) -> moved.add(file.name)
                else -> {
                    allHandled = false
                    StorageLog.write(
                        "[guides-key-migration] failed to move ${file.name} from $tag/$oldName into $tag/$baseName"
                    )
                }
            }
        }
        if (allHandled && !sourceDir.delete()) {
            StorageLog.write(
                "[guides-key-migration] failed to remove emptied $tag/$oldName after merging into $tag/$baseName"
            )
        }
        rewritePositionKeys(tag, oldName, baseName, moved = moved, discarded = discarded)
        StorageLog.write(
            "[guides-key-migration] merged $tag/$oldName into $tag/$baseName (${moved.size} moved, ${discarded.size} duplicate discarded)"
        )
        return allHandled
    }

    // moved == null rewrites every key under the old dir (plain rename, every file came along).
    // Otherwise this is the merge case: a filename in moved is rewritten to the new dir, a
    // filename in discarded (a confirmed, deleted duplicate) has its key dropped, and any other
    // filename - one that failed to move - keeps its original key untouched, since the file it
    // points at is still sitting at the old location.
    private fun rewritePositionKeys(
        tag: String,
        oldDirName: String,
        newDirName: String,
        moved: Set<String>?,
        discarded: Set<String> = emptySet(),
    ) {
        val file = positionsFile()
        val ini = IniParser.parse(file)
        if (ini.sections.isEmpty()) return
        val prefix = "$tag/$oldDirName/"
        var changed = false
        val rewritten = ini.sections.mapValues { (_, entries) ->
            val updated = LinkedHashMap<String, String>()
            for ((key, value) in entries) {
                if (!key.startsWith(prefix)) {
                    updated[key] = value
                    continue
                }
                val fileName = key.removePrefix(prefix)
                when {
                    moved == null || fileName in moved -> {
                        updated["$tag/$newDirName/$fileName"] = value
                        changed = true
                    }
                    fileName in discarded -> changed = true
                    else -> updated[key] = value
                }
            }
            updated
        }
        if (changed) IniWriter.write(file, rewritten)
    }

    private companion object {
        const val STAMP_NAME = ".guides_key_version"
        const val VERSION = 1
    }
}
