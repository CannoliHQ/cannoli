package dev.cannoli.scorza.launcher

import dev.cannoli.core.IniParser
import dev.cannoli.core.IniWriter
import dev.cannoli.core.RomKey
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.util.StorageLog
import java.io.File

class GuidesKeyMigration(
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

    private fun mergeInto(tag: String, sourceDir: File, targetDir: File, baseName: String): Boolean {
        val oldName = sourceDir.name
        val moved = mutableSetOf<String>()
        val discarded = mutableSetOf<String>()
        var allHandled = true
        val files = sourceDir.listFiles() ?: return false
        files.filter { it.isFile }.forEach { file ->
            val dest = File(targetDir, file.name)
            when {
                dest.exists() -> {
                    if (file.renameTo(File(sourceDir, "${file.name}.superseded"))) discarded.add(file.name)
                    else allHandled = false
                }
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
