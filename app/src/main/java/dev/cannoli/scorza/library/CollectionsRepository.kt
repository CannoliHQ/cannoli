package dev.cannoli.scorza.library

import androidx.sqlite.execSQL
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.CollectionType

class CollectionsRepository(private val db: CannoliDatabase) {
    data class CollectionRow(
        val id: Long,
        val displayName: String,
        val parentId: Long?,
        val sortOrder: Int,
        val type: CollectionType,
    )

    fun all(): List<CollectionRow> = query("ORDER BY sort_order, display_name COLLATE NOCASE")

    fun topLevel(): List<CollectionRow> =
        query("WHERE parent_id IS NULL AND collection_type = 'STANDARD' ORDER BY sort_order, display_name COLLATE NOCASE")

    fun favoritesId(): Long? {
        db.conn.prepare("SELECT id FROM collections WHERE collection_type = 'FAVORITES' LIMIT 1").use { stmt ->
            return if (stmt.step()) stmt.getLong(0) else null
        }
    }

    fun byId(id: Long): CollectionRow? = query("WHERE id = ?", listOf(id.toString())).firstOrNull()

    fun children(parentId: Long): List<CollectionRow> =
        query("WHERE parent_id = ? ORDER BY sort_order, display_name COLLATE NOCASE", listOf(parentId.toString()))

    fun romIdsIn(collectionId: Long): List<Long> {
        val out = mutableListOf<Long>()
        db.conn.prepare("SELECT rom_id FROM collection_members WHERE collection_id = ? AND rom_id IS NOT NULL ORDER BY sort_order").use { stmt ->
            stmt.bindLong(1, collectionId)
            while (stmt.step()) out.add(stmt.getLong(0))
        }
        return out
    }

    fun appIdsIn(collectionId: Long): List<Long> {
        val out = mutableListOf<Long>()
        db.conn.prepare("SELECT app_id FROM collection_members WHERE collection_id = ? AND app_id IS NOT NULL ORDER BY sort_order").use { stmt ->
            stmt.bindLong(1, collectionId)
            while (stmt.step()) out.add(stmt.getLong(0))
        }
        return out
    }

    fun favoriteRomIds(): Set<Long> {
        val favId = favoritesId() ?: return emptySet()
        return romIdsIn(favId).toSet()
    }

    fun favoriteAppIds(): Set<Long> {
        val favId = favoritesId() ?: return emptySet()
        return appIdsIn(favId).toSet()
    }

    fun isRomFavorited(romId: Long): Boolean {
        val favId = favoritesId() ?: return false
        return isMember(favId, LibraryRef.Rom(romId))
    }

    fun isAppFavorited(appId: Long): Boolean {
        val favId = favoritesId() ?: return false
        return isMember(favId, LibraryRef.App(appId))
    }

    fun isMember(collectionId: Long, ref: LibraryRef): Boolean {
        val sql = when (ref) {
            is LibraryRef.Rom -> "SELECT 1 FROM collection_members WHERE collection_id = ? AND rom_id = ? LIMIT 1"
            is LibraryRef.App -> "SELECT 1 FROM collection_members WHERE collection_id = ? AND app_id = ? LIMIT 1"
        }
        db.conn.prepare(sql).use { stmt ->
            stmt.bindLong(1, collectionId)
            stmt.bindLong(2, when (ref) { is LibraryRef.Rom -> ref.id; is LibraryRef.App -> ref.id })
            return stmt.step()
        }
    }

    fun collectionsContaining(ref: LibraryRef): Set<Long> {
        val sql = when (ref) {
            is LibraryRef.Rom -> "SELECT collection_id FROM collection_members WHERE rom_id = ?"
            is LibraryRef.App -> "SELECT collection_id FROM collection_members WHERE app_id = ?"
        }
        val out = mutableSetOf<Long>()
        db.conn.prepare(sql).use { stmt ->
            stmt.bindLong(1, when (ref) { is LibraryRef.Rom -> ref.id; is LibraryRef.App -> ref.id })
            while (stmt.step()) out.add(stmt.getLong(0))
        }
        return out
    }

    fun addMember(collectionId: Long, ref: LibraryRef) {
        if (isMember(collectionId, ref)) return
        val nextOrder = nextSortOrder(collectionId)
        db.conn.prepare(
            "INSERT INTO collection_members (collection_id, rom_id, app_id, sort_order) VALUES (?, ?, ?, ?)"
        ).use { stmt ->
            stmt.bindLong(1, collectionId)
            when (ref) {
                is LibraryRef.Rom -> { stmt.bindLong(2, ref.id); stmt.bindNull(3) }
                is LibraryRef.App -> { stmt.bindNull(2); stmt.bindLong(3, ref.id) }
            }
            stmt.bindLong(4, nextOrder.toLong())
            stmt.step()
        }
    }

    fun removeMember(collectionId: Long, ref: LibraryRef) {
        val sql = when (ref) {
            is LibraryRef.Rom -> "DELETE FROM collection_members WHERE collection_id = ? AND rom_id = ?"
            is LibraryRef.App -> "DELETE FROM collection_members WHERE collection_id = ? AND app_id = ?"
        }
        db.conn.prepare(sql).use { stmt ->
            stmt.bindLong(1, collectionId)
            stmt.bindLong(2, when (ref) { is LibraryRef.Rom -> ref.id; is LibraryRef.App -> ref.id })
            stmt.step()
        }
    }

    fun setMemberOrder(collectionId: Long, orderedRefs: List<LibraryRef>) {
        db.conn.execSQL("BEGIN")
        try {
            orderedRefs.forEachIndexed { index, ref ->
                val sql = when (ref) {
                    is LibraryRef.Rom -> "UPDATE collection_members SET sort_order = ? WHERE collection_id = ? AND rom_id = ?"
                    is LibraryRef.App -> "UPDATE collection_members SET sort_order = ? WHERE collection_id = ? AND app_id = ?"
                }
                db.conn.prepare(sql).use { stmt ->
                    stmt.bindLong(1, index.toLong())
                    stmt.bindLong(2, collectionId)
                    stmt.bindLong(3, when (ref) { is LibraryRef.Rom -> ref.id; is LibraryRef.App -> ref.id })
                    stmt.step()
                }
            }
            db.conn.execSQL("COMMIT")
        } catch (t: Throwable) {
            db.conn.execSQL("ROLLBACK")
            throw t
        }
    }

    fun delete(collectionId: Long) {
        db.conn.prepare("DELETE FROM collections WHERE id = ?").use { stmt ->
            stmt.bindLong(1, collectionId)
            stmt.step()
        }
    }

    fun setCollectionOrder(orderedIds: List<Long>) {
        db.conn.execSQL("BEGIN")
        try {
            orderedIds.forEachIndexed { index, id ->
                db.conn.prepare("UPDATE collections SET sort_order = ? WHERE id = ?").use { stmt ->
                    stmt.bindLong(1, index.toLong())
                    stmt.bindLong(2, id)
                    stmt.step()
                }
            }
            db.conn.execSQL("COMMIT")
        } catch (t: Throwable) {
            db.conn.execSQL("ROLLBACK")
            throw t
        }
    }

    private fun nextSortOrder(collectionId: Long): Int {
        db.conn.prepare("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM collection_members WHERE collection_id = ?").use { stmt ->
            stmt.bindLong(1, collectionId)
            stmt.step()
            return stmt.getInt(0)
        }
    }

    private fun query(suffix: String, args: List<String> = emptyList()): List<CollectionRow> {
        val sql = "SELECT id, display_name, parent_id, sort_order, collection_type FROM collections $suffix"
        val out = mutableListOf<CollectionRow>()
        db.conn.prepare(sql).use { stmt ->
            args.forEachIndexed { index, value -> stmt.bindText(index + 1, value) }
            while (stmt.step()) {
                out.add(
                    CollectionRow(
                        id = stmt.getLong(0),
                        displayName = stmt.getText(1),
                        parentId = if (stmt.isNull(2)) null else stmt.getLong(2),
                        sortOrder = stmt.getInt(3),
                        type = CollectionType.fromColumn(stmt.getText(4)),
                    )
                )
            }
        }
        return out
    }
}
