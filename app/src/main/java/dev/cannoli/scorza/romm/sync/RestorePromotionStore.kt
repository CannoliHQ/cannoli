package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.db.queryAll
import dev.cannoli.scorza.db.queryOne

// A deferred "make my restored save the head" intent, recorded when a backup restore could not
// promote to the server immediately (offline). The next sync applies it: force-upload if the
// server head still equals baseHead, else escalate as a conflict.
data class RestorePromotion(
    val gameKey: String,
    val slot: String,
    val targetHash: String,
    val baseHead: String?,
    val createdAt: Long,
)

class RestorePromotionStore(private val db: CannoliDatabase) {

    fun upsert(p: RestorePromotion) = db.execute(
        "INSERT OR REPLACE INTO restore_promotions (game_key, slot, target_hash, base_head, created_at) VALUES (?, ?, ?, ?, ?)",
        p.gameKey, p.slot, p.targetHash, p.baseHead, p.createdAt,
    )

    fun get(gameKey: String, slot: String): RestorePromotion? = db.queryOne(
        "SELECT game_key, slot, target_hash, base_head, created_at FROM restore_promotions WHERE game_key = ? AND slot = ?",
        gameKey, slot,
    ) { it.toRow() }

    fun all(): List<RestorePromotion> = db.queryAll(
        "SELECT game_key, slot, target_hash, base_head, created_at FROM restore_promotions",
    ) { it.toRow() }

    fun delete(gameKey: String, slot: String) =
        db.execute("DELETE FROM restore_promotions WHERE game_key = ? AND slot = ?", gameKey, slot)

    private fun androidx.sqlite.SQLiteStatement.toRow() = RestorePromotion(
        gameKey = getText(0),
        slot = getText(1),
        targetHash = getText(2),
        baseHead = if (isNull(3)) null else getText(3),
        createdAt = getLong(4),
    )
}
