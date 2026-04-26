package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteStatement
import dev.cannoli.scorza.model.CollectionType

class CollectionsRepository(private val db: CannoliDatabase) {
    data class CollectionRow(
        val id: Long,
        val displayName: String,
        val parentId: Long?,
        val sortOrder: Int,
        val type: CollectionType,
    )

    fun all(): List<CollectionRow> =
        db.queryAll("$BASE_SELECT ORDER BY sort_order, display_name COLLATE NOCASE", mapper = ::rowToCollection)

    fun topLevel(): List<CollectionRow> = db.queryAll(
        "$BASE_SELECT WHERE parent_id IS NULL AND collection_type = 'STANDARD' ORDER BY sort_order, display_name COLLATE NOCASE",
        mapper = ::rowToCollection,
    )

    fun byId(id: Long): CollectionRow? = db.queryOne(
        "$BASE_SELECT WHERE id = ?", id, mapper = ::rowToCollection,
    )

    fun children(parentId: Long): List<CollectionRow> = db.queryAll(
        "$BASE_SELECT WHERE parent_id = ? ORDER BY sort_order, display_name COLLATE NOCASE",
        parentId, mapper = ::rowToCollection,
    )

    fun favoritesId(): Long? = db.queryOne(
        "SELECT id FROM collections WHERE collection_type = 'FAVORITES' LIMIT 1",
    ) { it.getLong(0) }

    fun romIdsIn(collectionId: Long): List<Long> = readMemberIds("rom_id", collectionId)
    fun appIdsIn(collectionId: Long): List<Long> = readMemberIds("app_id", collectionId)

    fun favoriteRomIds(): Set<Long> = favoritesId()?.let { romIdsIn(it).toSet() } ?: emptySet()
    fun favoriteAppIds(): Set<Long> = favoritesId()?.let { appIdsIn(it).toSet() } ?: emptySet()

    fun isRomFavorited(romId: Long): Boolean =
        favoritesId()?.let { isMember(it, LibraryRef.Rom(romId)) } == true

    fun isAppFavorited(appId: Long): Boolean =
        favoritesId()?.let { isMember(it, LibraryRef.App(appId)) } == true

    fun isMember(collectionId: Long, ref: LibraryRef): Boolean = db.queryOne(
        "SELECT 1 FROM collection_members WHERE collection_id = ? AND ${ref.column()} = ? LIMIT 1",
        collectionId, ref.id,
    ) { true } ?: false

    fun collectionsContaining(ref: LibraryRef): Set<Long> = db.queryAll(
        "SELECT collection_id FROM collection_members WHERE ${ref.column()} = ?",
        ref.id,
    ) { it.getLong(0) }.toSet()

    fun addMember(collectionId: Long, ref: LibraryRef) {
        if (isMember(collectionId, ref)) return
        val nextOrder = nextSortOrder(collectionId)
        val (romId, appId) = when (ref) {
            is LibraryRef.Rom -> ref.id to null
            is LibraryRef.App -> null to ref.id
        }
        db.execute(
            "INSERT INTO collection_members (collection_id, rom_id, app_id, sort_order) VALUES (?, ?, ?, ?)",
            collectionId, romId, appId, nextOrder.toLong(),
        )
    }

    fun removeMember(collectionId: Long, ref: LibraryRef) = db.execute(
        "DELETE FROM collection_members WHERE collection_id = ? AND ${ref.column()} = ?",
        collectionId, ref.id,
    )

    fun setMemberOrder(collectionId: Long, orderedRefs: List<LibraryRef>) = db.transaction { conn ->
        orderedRefs.forEachIndexed { index, ref ->
            conn.execute(
                "UPDATE collection_members SET sort_order = ? WHERE collection_id = ? AND ${ref.column()} = ?",
                index.toLong(), collectionId, ref.id,
            )
        }
    }

    fun create(displayName: String, type: CollectionType = CollectionType.STANDARD): Long =
        db.executeReturningId(
            "INSERT INTO collections (display_name, collection_type) VALUES (?, ?)",
            displayName, type.name,
        )

    fun rename(collectionId: Long, newName: String) = db.execute(
        "UPDATE collections SET display_name = ? WHERE id = ?",
        newName, collectionId,
    )

    fun setParent(collectionId: Long, parentId: Long?) {
        if (parentId == collectionId) return
        if (parentId != null && wouldCycle(child = collectionId, candidateParent = parentId)) return
        if (parentId == null) {
            db.execute("UPDATE collections SET parent_id = NULL WHERE id = ?", collectionId)
        } else {
            db.execute("UPDATE collections SET parent_id = ? WHERE id = ?", parentId, collectionId)
        }
    }

    fun ancestors(collectionId: Long): List<CollectionRow> {
        val out = mutableListOf<CollectionRow>()
        val seen = mutableSetOf(collectionId)
        var current = byId(collectionId)?.parentId
        while (current != null && seen.add(current)) {
            val row = byId(current) ?: break
            out.add(row)
            current = row.parentId
        }
        return out
    }

    fun delete(collectionId: Long) = db.execute("DELETE FROM collections WHERE id = ?", collectionId)

    fun setCollectionOrder(orderedIds: List<Long>) = db.transaction { conn ->
        orderedIds.forEachIndexed { index, id ->
            conn.execute("UPDATE collections SET sort_order = ? WHERE id = ?", index.toLong(), id)
        }
    }

    private fun wouldCycle(child: Long, candidateParent: Long): Boolean {
        val seen = mutableSetOf(child)
        var current: Long? = candidateParent
        while (current != null) {
            if (!seen.add(current)) return true
            current = byId(current)?.parentId
        }
        return false
    }

    private fun nextSortOrder(collectionId: Long): Int = db.queryOne(
        "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM collection_members WHERE collection_id = ?",
        collectionId,
    ) { it.getInt(0) } ?: 0

    private fun readMemberIds(column: String, collectionId: Long): List<Long> = db.queryAll(
        "SELECT $column FROM collection_members WHERE collection_id = ? AND $column IS NOT NULL ORDER BY sort_order",
        collectionId,
    ) { it.getLong(0) }

    private fun rowToCollection(stmt: SQLiteStatement) = CollectionRow(
        id = stmt.getLong(0),
        displayName = stmt.getText(1),
        parentId = if (stmt.isNull(2)) null else stmt.getLong(2),
        sortOrder = stmt.getInt(3),
        type = CollectionType.from(stmt.getText(4)),
    )

    private fun LibraryRef.column(): String = when (this) {
        is LibraryRef.Rom -> "rom_id"
        is LibraryRef.App -> "app_id"
    }

    private companion object {
        const val BASE_SELECT = "SELECT id, display_name, parent_id, sort_order, collection_type FROM collections"
    }
}
