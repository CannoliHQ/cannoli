package dev.cannoli.scorza.db

import dev.cannoli.scorza.util.ArtworkLookup
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

    fun scanPlatform(platformTag: String, isArcade: Boolean = false): SyncCounts {
        val tag = platformTag.uppercase()
        ensurePlatformRow(tag)
        val result = walker.walk(tag, isArcade) ?: return clearPlatform(tag).also {
            ScanLog.write("scanPlatform $tag: no rom dir, cleared ${it.removed}")
        }
        val storedMtime = readLastScannedMtime(tag)
        if (storedMtime != MTIME_UNSET && storedMtime == result.mtime) {
            return SyncCounts(0, 0, 0)
        }
        artwork.invalidate(tag)
        walker.invalidateNameMap(result.tagDir)
        val counts = sync(tag, result.roms)
        writeLastScannedMtime(tag, result.mtime)
        ScanLog.write("scanPlatform $tag: +${counts.inserted} -${counts.removed} ~${counts.updated}")
        return counts
    }

    fun invalidatePlatform(platformTag: String) {
        writeLastScannedMtime(platformTag.uppercase(), MTIME_UNSET)
    }

    fun ensureReservedPlatformTag(tag: String) = ensurePlatformRow(tag)

    private fun readLastScannedMtime(tag: String): Long = db.conn.queryOne(
        "SELECT last_scanned_mtime FROM platforms WHERE tag = ?", tag,
    ) { it.getLong(0) } ?: MTIME_UNSET

    private fun writeLastScannedMtime(tag: String, mtime: Long) = db.conn.execute(
        "UPDATE platforms SET last_scanned_mtime = ? WHERE tag = ?",
        mtime, tag,
    )

    private fun sync(tag: String, scanned: List<RomDirectoryWalker.ScannedRom>): SyncCounts {
        data class ExistingRow(val id: Long, val displayName: String, val tags: String?, val discPaths: String?)
        val existing = db.conn.queryAll(
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

        db.conn.transaction {
            db.conn.prepare("INSERT INTO roms (path, platform_tag, display_name, tags, disc_paths) VALUES (?, ?, ?, ?, ?)").use { insertStmt ->
                db.conn.prepare("UPDATE roms SET display_name = ?, tags = ?, disc_paths = ? WHERE id = ?").use { updateStmt ->
                    db.conn.prepare("DELETE FROM roms WHERE id = ?").use { deleteStmt ->
                        for (rom in scanned) {
                            val current = existing[rom.relativePath]
                            val discJson = rom.discPaths?.let { JSONArray(it).toString() }
                            if (current == null) {
                                insertStmt.reset()
                                insertStmt.bindText(1, rom.relativePath)
                                insertStmt.bindText(2, tag)
                                insertStmt.bindText(3, rom.displayName)
                                if (rom.tags != null) insertStmt.bindText(4, rom.tags) else insertStmt.bindNull(4)
                                if (discJson != null) insertStmt.bindText(5, discJson) else insertStmt.bindNull(5)
                                insertStmt.step()
                                inserted++
                            } else if (current.displayName != rom.displayName || current.tags != rom.tags || current.discPaths != discJson) {
                                updateStmt.reset()
                                updateStmt.bindText(1, rom.displayName)
                                if (rom.tags != null) updateStmt.bindText(2, rom.tags) else updateStmt.bindNull(2)
                                if (discJson != null) updateStmt.bindText(3, discJson) else updateStmt.bindNull(3)
                                updateStmt.bindLong(4, current.id)
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
        val count = db.conn.queryOne("SELECT COUNT(*) FROM roms WHERE platform_tag = ?", tag) { it.getInt(0) } ?: 0
        if (count == 0) return SyncCounts(0, 0, 0)
        db.conn.execute("DELETE FROM roms WHERE platform_tag = ?", tag)
        return SyncCounts(0, 0, count)
    }

    private fun ensurePlatformRow(tag: String) = db.conn.execute(
        "INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)",
        tag, tag,
    )

    private companion object {
        const val MTIME_UNSET = 0L
    }
}
