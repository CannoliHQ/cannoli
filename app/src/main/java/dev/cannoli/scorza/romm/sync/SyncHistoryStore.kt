package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.db.queryAll

enum class SyncDirection { UPLOAD, DOWNLOAD, CONFLICT, ERROR }

data class SyncHistoryEntry(
    val gameKey: String,
    val displayName: String,
    val direction: SyncDirection,
    val detail: String?,
    val createdAt: Long,
)

class SyncHistoryStore(private val db: CannoliDatabase, private val cap: Int = 100) {

    fun add(entry: SyncHistoryEntry) {
        db.execute(
            "INSERT INTO sync_history (game_key, display_name, direction, detail, created_at) VALUES (?, ?, ?, ?, ?)",
            entry.gameKey, entry.displayName, entry.direction.name, entry.detail, entry.createdAt,
        )
        db.execute(
            "DELETE FROM sync_history WHERE id NOT IN (SELECT id FROM sync_history ORDER BY id DESC LIMIT ?)",
            cap.toLong(),
        )
    }

    fun recent(limit: Int = cap): List<SyncHistoryEntry> = db.queryAll(
        "SELECT game_key, display_name, direction, detail, created_at FROM sync_history ORDER BY created_at DESC, id DESC LIMIT ?",
        limit.toLong(),
    ) {
        SyncHistoryEntry(
            gameKey = it.getText(0),
            displayName = it.getText(1),
            direction = SyncDirection.valueOf(it.getText(2)),
            detail = if (it.isNull(3)) null else it.getText(3),
            createdAt = it.getLong(4),
        )
    }
}
