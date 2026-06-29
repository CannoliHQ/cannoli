package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.db.queryAll
import dev.cannoli.scorza.db.queryOne

data class PendingConflict(
    val gameKey: String,
    val romId: Int,
    val displayName: String,
    val serverSaveId: Int?,
    val serverContentHash: String?,
    val serverUpdatedAt: String?,
    val detectedAt: Long,
    val dismissedHash: String?,
)

class PendingConflictStore(private val db: CannoliDatabase) {

    fun upsert(c: PendingConflict) = db.execute(
        "INSERT OR REPLACE INTO pending_conflicts (game_key, rom_id, display_name, server_save_id, server_content_hash, server_updated_at, detected_at, dismissed_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        c.gameKey, c.romId, c.displayName, c.serverSaveId, c.serverContentHash, c.serverUpdatedAt, c.detectedAt, c.dismissedHash,
    )

    fun all(): List<PendingConflict> = db.queryAll(
        "SELECT game_key, rom_id, display_name, server_save_id, server_content_hash, server_updated_at, detected_at, dismissed_hash FROM pending_conflicts ORDER BY detected_at DESC",
    ) { it.toConflict() }

    fun get(gameKey: String): PendingConflict? = db.queryOne(
        "SELECT game_key, rom_id, display_name, server_save_id, server_content_hash, server_updated_at, detected_at, dismissed_hash FROM pending_conflicts WHERE game_key = ?",
        gameKey,
    ) { it.toConflict() }

    fun delete(gameKey: String) = db.execute("DELETE FROM pending_conflicts WHERE game_key = ?", gameKey)

    fun count(): Int = db.queryOne("SELECT COUNT(*) FROM pending_conflicts") { it.getInt(0) } ?: 0

    fun markDismissed(gameKey: String, serverHash: String?) =
        db.execute("UPDATE pending_conflicts SET dismissed_hash = ? WHERE game_key = ?", serverHash, gameKey)

    private fun androidx.sqlite.SQLiteStatement.toConflict() = PendingConflict(
        gameKey = getText(0),
        romId = getInt(1),
        displayName = getText(2),
        serverSaveId = if (isNull(3)) null else getInt(3),
        serverContentHash = if (isNull(4)) null else getText(4),
        serverUpdatedAt = if (isNull(5)) null else getText(5),
        detectedAt = getLong(6),
        dismissedHash = if (isNull(7)) null else getText(7),
    )
}
