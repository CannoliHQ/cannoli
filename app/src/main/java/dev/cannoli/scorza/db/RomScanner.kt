package dev.cannoli.scorza.db

import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.NaturalSort
import dev.cannoli.scorza.util.RomDirectoryWalker
import dev.cannoli.scorza.util.ScanLog
import org.json.JSONArray

/**
 * Bridges the file-system view of a platform (via [RomDirectoryWalker]) into the roms table.
 * Owns the mtime gate and the diff-and-sync logic; the walker owns everything filesystem.
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

    fun consumeLauncherMutation(platformTag: String): Boolean =
        launcherMutatedTags.remove(platformTag.uppercase())

    fun scanPlatform(platformTag: String, isArcade: Boolean = false): SyncCounts {
        val tag = platformTag.uppercase()
        ensurePlatformRow(tag)
        val result = walker.walk(tag, isArcade) ?: return clearPlatform(tag).also {
            ScanLog.write("scanPlatform $tag: no rom dir, cleared ${it.removed}")
        }
        applyRekeys(tag, result.rekeys)
        // Invalidate before the mtime gate: art lives in a sibling directory the gate
        // does not watch, so newly added art must refresh even when the ROM tree is unchanged.
        artwork.invalidate(tag)
        val storedMtime = readLastScannedMtime(tag)
        if (result.rekeys.isEmpty() && storedMtime != MTIME_UNSET && storedMtime == result.mtime) {
            return SyncCounts(0, 0, 0)
        }
        walker.invalidateNameMap(result.tagDir)
        val counts = sync(tag, result.roms)
        writeLastScannedMtime(tag, result.mtime)
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

    fun invalidatePlatform(platformTag: String) {
        val tag = platformTag.uppercase()
        artwork.invalidate(tag)
        writeLastScannedMtime(tag, MTIME_UNSET)
        launcherMutatedTags.add(tag)
    }

    fun ensureReservedPlatformTag(tag: String) = ensurePlatformRow(tag)

    fun lastScannedMtime(platformTag: String): Long = readLastScannedMtime(platformTag.uppercase())

    private fun readLastScannedMtime(tag: String): Long = db.queryOne(
        "SELECT last_scanned_mtime FROM platforms WHERE tag = ?", tag,
    ) { it.getLong(0) } ?: MTIME_UNSET

    private fun writeLastScannedMtime(tag: String, mtime: Long) = db.execute(
        "UPDATE platforms SET last_scanned_mtime = ? WHERE tag = ?",
        mtime, tag,
    )

    private fun sync(tag: String, scanned: List<RomDirectoryWalker.ScannedRom>): SyncCounts {
        data class ExistingRow(val id: Long, val displayName: String, val tags: String?, val discPaths: String?)
        val existing = db.queryAll(
            "SELECT id, path, display_name, tags, disc_paths FROM roms WHERE platform_tag = ?", tag,
        ) { stmt ->
            stmt.getText(1) to ExistingRow(
                id = stmt.getLong(0),
                displayName = stmt.getText(2),
                tags = if (stmt.isNull(3)) null else stmt.getText(3),
                discPaths = if (stmt.isNull(4)) null else stmt.getText(4),
            )
        }.toMap()

        val scannedByPath = scanned.associateBy { it.relativePath }
        var inserted = 0
        var updated = 0
        var removed = 0

        db.transaction { conn ->
            conn.prepare("INSERT INTO roms (path, platform_tag, display_name, sort_key, tags, disc_paths) VALUES (?, ?, ?, ?, ?, ?)").use { insertStmt ->
                conn.prepare("UPDATE roms SET display_name = ?, sort_key = ?, tags = ?, disc_paths = ? WHERE id = ?").use { updateStmt ->
                    conn.prepare("DELETE FROM roms WHERE id = ?").use { deleteStmt ->
                        for (rom in scannedByPath.values) {
                            val current = existing[rom.relativePath]
                            val discJson = rom.discPaths?.let { JSONArray(it).toString() }
                            if (current == null) {
                                insertStmt.reset()
                                insertStmt.bindText(1, rom.relativePath)
                                insertStmt.bindText(2, tag)
                                insertStmt.bindText(3, rom.displayName)
                                insertStmt.bindText(4, NaturalSort.toSortKey(rom.displayName))
                                if (rom.tags != null) insertStmt.bindText(5, rom.tags) else insertStmt.bindNull(5)
                                if (discJson != null) insertStmt.bindText(6, discJson) else insertStmt.bindNull(6)
                                insertStmt.step()
                                inserted++
                            } else if (current.displayName != rom.displayName || current.tags != rom.tags || current.discPaths != discJson) {
                                updateStmt.reset()
                                updateStmt.bindText(1, rom.displayName)
                                updateStmt.bindText(2, NaturalSort.toSortKey(rom.displayName))
                                if (rom.tags != null) updateStmt.bindText(3, rom.tags) else updateStmt.bindNull(3)
                                if (discJson != null) updateStmt.bindText(4, discJson) else updateStmt.bindNull(4)
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

    private companion object {
        const val MTIME_UNSET = 0L
    }
}
