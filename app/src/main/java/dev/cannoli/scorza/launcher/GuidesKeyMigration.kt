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
            if (dir.isDirectory) migrate(dir)
            dir.mkdirs()
            stamp.writeText(VERSION.toString())
        }.onFailure {
            StorageLog.write("[guides-key-migration] failed: ${it::class.java.simpleName} ${it.message}")
        }
    }

    private fun migrate(guidesRoot: File) {
        val byTag = roms().groupBy { it.platformTag }
        val tagDirs = guidesRoot.listFiles { f -> f.isDirectory } ?: return
        for (tagDir in tagDirs) migrateTag(tagDir, byTag[tagDir.name].orEmpty())
    }

    private fun migrateTag(tagDir: File, tagRoms: List<Rom>) {
        val baseNames = tagRoms.mapTo(mutableSetOf()) { RomKey.baseName(it.path) }
        val byDisplayName = tagRoms.groupBy { it.displayName }
        val dirs = tagDir.listFiles { f -> f.isDirectory } ?: return
        for (dir in dirs) {
            val name = dir.name
            if (name in baseNames) continue
            val matches = byDisplayName[name].orEmpty()
            when {
                matches.size == 1 -> renameToBaseName(tagDir.name, dir, RomKey.baseName(matches[0].path))
                matches.size > 1 -> StorageLog.write(
                    "[guides-key-migration] skip ${tagDir.name}/$name: matches ${matches.size} roms' display names"
                )
                else -> StorageLog.write("[guides-key-migration] skip ${tagDir.name}/$name: no matching rom")
            }
        }
    }

    private fun renameToBaseName(tag: String, sourceDir: File, baseName: String) {
        if (baseName == sourceDir.name) return
        val targetDir = File(sourceDir.parentFile, baseName)
        if (!targetDir.exists()) {
            if (sourceDir.renameTo(targetDir)) {
                rewritePositionKeys(tag, sourceDir.name, baseName, keep = null)
                StorageLog.write("[guides-key-migration] renamed $tag/${sourceDir.name} -> $tag/$baseName")
            } else {
                StorageLog.write("[guides-key-migration] failed to rename $tag/${sourceDir.name} -> $tag/$baseName")
            }
            return
        }
        mergeInto(tag, sourceDir, targetDir, baseName)
    }

    // A duplicate filename means the target already holds this guide, so the source's copy is
    // discarded rather than left behind as a dir the app can never resolve again. The source dir
    // itself is only removed once every file in it was accounted for; a failed move leaves it
    // (and whatever remains inside it) in place instead of losing anything.
    private fun mergeInto(tag: String, sourceDir: File, targetDir: File, baseName: String) {
        val oldName = sourceDir.name
        val moved = mutableSetOf<String>()
        var duplicates = 0
        var allHandled = true
        sourceDir.listFiles()?.filter { it.isFile }?.forEach { file ->
            val dest = File(targetDir, file.name)
            when {
                dest.exists() -> if (file.delete()) duplicates++ else allHandled = false
                file.renameTo(dest) -> moved.add(file.name)
                else -> {
                    allHandled = false
                    StorageLog.write(
                        "[guides-key-migration] failed to move ${file.name} from $tag/$oldName into $tag/$baseName"
                    )
                }
            }
        }
        if (allHandled) sourceDir.delete()
        rewritePositionKeys(tag, oldName, baseName, keep = moved)
        StorageLog.write(
            "[guides-key-migration] merged $tag/$oldName into $tag/$baseName (${moved.size} moved, $duplicates duplicate discarded)"
        )
    }

    // keep == null rewrites every key under the old dir (plain rename, every file came along).
    // A non-null set is the merge case: only filenames that actually moved keep a position, and
    // a key left behind by a duplicate is dropped rather than pointing at a directory that no
    // longer exists.
    private fun rewritePositionKeys(tag: String, oldDirName: String, newDirName: String, keep: Set<String>?) {
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
                changed = true
                val fileName = key.removePrefix(prefix)
                if (keep == null || fileName in keep) updated["$tag/$newDirName/$fileName"] = value
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
