package dev.cannoli.scorza.db

import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.NaturalSort
import dev.cannoli.scorza.util.RomDirectoryWalker
import dev.cannoli.scorza.util.ScanLog
import dev.cannoli.scorza.util.TextNormalizer

/**
 * Bridges the file-system view of a platform (via [RomDirectoryWalker]) into the roms table.
 * Always walks and syncs; [sync] is idempotent and writes nothing when the walk output matches
 * the existing rows.
 */
class RomScanner(
    private val db: CannoliDatabase,
    private val walker: RomDirectoryWalker,
    private val artwork: ArtworkLookup,
) {
    data class SyncCounts(val inserted: Int, val updated: Int, val removed: Int)

    // Tags the launcher itself just mutated (rename, delete, organize). The next scan of such a
    // tag reconciles a change the user already saw happen in the UI, so it must not raise the
    // "Game list updated" OSD the way a background/external change does.
    private val launcherMutatedTags = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun markLauncherMutation(platformTag: String) {
        launcherMutatedTags.add(platformTag.uppercase())
    }

    fun consumeLauncherMutation(platformTag: String): Boolean =
        launcherMutatedTags.remove(platformTag.uppercase())

    fun scanPlatform(platformTag: String, isArcade: Boolean = false): SyncCounts {
        val tag = platformTag.uppercase()
        ensurePlatformRow(tag)
        val result = walker.walk(tag, isArcade) ?: return clearPlatform(tag).also {
            ScanLog.write("scanPlatform $tag: no rom dir, cleared ${it.removed}")
        }
        applyRekeys(tag, result.rekeys)
        artwork.invalidate(tag)
        walker.invalidateNameMap(result.tagDir)
        val counts = sync(tag, result.roms)
        ScanLog.write("scanPlatform $tag: +${counts.inserted} -${counts.removed} ~${counts.updated}")
        return counts
    }

    private fun applyRekeys(tag: String, rekeys: List<RomDirectoryWalker.RekeyMove>) {
        if (rekeys.isEmpty()) return
        db.transaction { conn ->
            conn.prepare("UPDATE roms SET path = ? WHERE platform_tag = ? AND path = ?").use { stmt ->
                for (move in rekeys) {
                    stmt.reset()
                    stmt.bindText(1, move.newRelPath)
                    stmt.bindText(2, tag)
                    stmt.bindText(3, move.oldRelPath)
                    stmt.step()
                }
            }
        }
    }

    fun ensureReservedPlatformTag(tag: String) = ensurePlatformRow(tag)

    private fun sync(tag: String, scanned: List<RomDirectoryWalker.ScannedRom>): SyncCounts {
        data class ExistingRow(val id: Long, val displayName: String, val tags: String?)
        val existing = db.queryAll(
            "SELECT id, path, display_name, tags FROM roms WHERE platform_tag = ?", tag,
        ) { stmt ->
            stmt.getText(1) to ExistingRow(
                id = stmt.getLong(0),
                displayName = stmt.getText(2),
                tags = if (stmt.isNull(3)) null else stmt.getText(3),
            )
        }.toMap()

        val scannedByPath = scanned.associateBy { it.relativePath }
        var inserted = 0
        var updated = 0
        var removed = 0

        db.transaction { conn ->
            conn.prepare("INSERT INTO roms (path, platform_tag, display_name, sort_key, tags, name_normalized) VALUES (?, ?, ?, ?, ?, ?)").use { insertStmt ->
                conn.prepare("UPDATE roms SET display_name = ?, sort_key = ?, tags = ?, name_normalized = ? WHERE id = ?").use { updateStmt ->
                    conn.prepare("DELETE FROM roms WHERE id = ?").use { deleteStmt ->
                        for (rom in scannedByPath.values) {
                            val current = existing[rom.relativePath]
                            if (current == null) {
                                insertStmt.reset()
                                insertStmt.bindText(1, rom.relativePath)
                                insertStmt.bindText(2, tag)
                                insertStmt.bindText(3, rom.displayName)
                                insertStmt.bindText(4, NaturalSort.toSortKey(rom.displayName))
                                if (rom.tags != null) insertStmt.bindText(5, rom.tags) else insertStmt.bindNull(5)
                                insertStmt.bindText(6, TextNormalizer.normalize(rom.displayName))
                                insertStmt.step()
                                inserted++
                            } else if (current.displayName != rom.displayName || current.tags != rom.tags) {
                                updateStmt.reset()
                                updateStmt.bindText(1, rom.displayName)
                                updateStmt.bindText(2, NaturalSort.toSortKey(rom.displayName))
                                if (rom.tags != null) updateStmt.bindText(3, rom.tags) else updateStmt.bindNull(3)
                                updateStmt.bindText(4, TextNormalizer.normalize(rom.displayName))
                                updateStmt.bindLong(5, current.id)
                                updateStmt.step()
                                updated++
                            }
                        }
                        for ((path, row) in existing) {
                            if (path in scannedByPath) continue
                            deleteStmt.reset()
                            deleteStmt.bindLong(1, row.id)
                            deleteStmt.step()
                            removed++
                        }
                    }
                }
            }
        }

        return SyncCounts(inserted, updated, removed)
    }

    private fun clearPlatform(tag: String): SyncCounts {
        val count = db.queryOne("SELECT COUNT(*) FROM roms WHERE platform_tag = ?", tag) { it.getInt(0) } ?: 0
        if (count == 0) return SyncCounts(0, 0, 0)
        db.execute("DELETE FROM roms WHERE platform_tag = ?", tag)
        return SyncCounts(0, 0, count)
    }

    private fun ensurePlatformRow(tag: String) = db.execute(
        "INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)",
        tag, tag,
    )
}
