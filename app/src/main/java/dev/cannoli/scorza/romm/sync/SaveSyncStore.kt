package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.db.queryAll
import dev.cannoli.scorza.db.queryOne

const val DEFAULT_SLOT = "autosave"

data class SaveSyncRow(
    val gameKey: String,
    val slot: String,
    val rommRomId: Int,
    val rommSaveId: Int?,
    val lastSyncedAt: String?,
    val lastUploadedHash: String?,
    val localContentHash: String?,
    val serverUpdatedAt: String?,
    val updatedAt: Long,
)

class SaveSyncStore(private val db: CannoliDatabase) {

    fun get(gameKey: String, slot: String): SaveSyncRow? = db.queryOne(
        "SELECT game_key, slot, romm_rom_id, romm_save_id, last_synced_at, last_uploaded_hash, local_content_hash, server_updated_at, updated_at FROM save_sync WHERE game_key = ? AND slot = ?",
        gameKey, slot,
    ) { it.toRow() }

    fun listSlots(gameKey: String): List<SaveSyncRow> = db.queryAll(
        "SELECT game_key, slot, romm_rom_id, romm_save_id, last_synced_at, last_uploaded_hash, local_content_hash, server_updated_at, updated_at FROM save_sync WHERE game_key = ? ORDER BY slot",
        gameKey,
    ) { it.toRow() }

    fun upsert(row: SaveSyncRow) = db.execute(
        "INSERT OR REPLACE INTO save_sync (game_key, slot, romm_rom_id, romm_save_id, last_synced_at, last_uploaded_hash, local_content_hash, server_updated_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        row.gameKey, row.slot, row.rommRomId, row.rommSaveId, row.lastSyncedAt, row.lastUploadedHash, row.localContentHash, row.serverUpdatedAt, row.updatedAt,
    )

    fun delete(gameKey: String, slot: String) =
        db.execute("DELETE FROM save_sync WHERE game_key = ? AND slot = ?", gameKey, slot)

    fun activeSlot(gameKey: String): String =
        db.queryOne("SELECT active_slot FROM save_slot_active WHERE game_key = ?", gameKey) { it.getText(0) } ?: DEFAULT_SLOT

    fun setActiveSlot(gameKey: String, slot: String) = db.execute(
        "INSERT OR REPLACE INTO save_slot_active (game_key, active_slot) VALUES (?, ?)", gameKey, slot,
    )

    private fun androidx.sqlite.SQLiteStatement.toRow() = SaveSyncRow(
        gameKey = getText(0),
        slot = getText(1),
        rommRomId = getInt(2),
        rommSaveId = if (isNull(3)) null else getInt(3),
        lastSyncedAt = if (isNull(4)) null else getText(4),
        lastUploadedHash = if (isNull(5)) null else getText(5),
        localContentHash = if (isNull(6)) null else getText(6),
        serverUpdatedAt = if (isNull(7)) null else getText(7),
        updatedAt = getLong(8),
    )
}
