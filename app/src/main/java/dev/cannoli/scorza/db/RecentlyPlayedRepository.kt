package dev.cannoli.scorza.db

class RecentlyPlayedRepository(private val db: CannoliDatabase) {
    data class Entry(val ref: LibraryRef, val lastPlayedAt: Long)

    fun recent(limit: Int = 10): List<Entry> {
        val out = mutableListOf<Entry>()
        db.conn.query("""
            SELECT 'rom' AS kind, id, last_played_at FROM roms WHERE last_played_at IS NOT NULL
            UNION ALL
            SELECT 'app' AS kind, id, last_played_at FROM apps WHERE last_played_at IS NOT NULL
            ORDER BY last_played_at DESC
            LIMIT ?
        """.trimIndent()) { stmt ->
            stmt.bindLong(1, limit.toLong())
            while (stmt.step()) {
                val ref: LibraryRef = if (stmt.getText(0) == "rom") LibraryRef.Rom(stmt.getLong(1))
                else LibraryRef.App(stmt.getLong(1))
                out.add(Entry(ref, stmt.getLong(2)))
            }
        }
        return out
    }

    fun record(ref: LibraryRef, timestamp: Long = System.currentTimeMillis()) {
        val sql = when (ref) {
            is LibraryRef.Rom -> "UPDATE roms SET last_played_at = ? WHERE id = ?"
            is LibraryRef.App -> "UPDATE apps SET last_played_at = ? WHERE id = ?"
        }
        db.conn.execute(sql, timestamp, ref.id)
    }

    fun clear(ref: LibraryRef) {
        val sql = when (ref) {
            is LibraryRef.Rom -> "UPDATE roms SET last_played_at = NULL WHERE id = ?"
            is LibraryRef.App -> "UPDATE apps SET last_played_at = NULL WHERE id = ?"
        }
        db.conn.execute(sql, ref.id)
    }

    fun hasAny(): Boolean = db.conn.query("""
        SELECT 1 WHERE EXISTS(SELECT 1 FROM roms WHERE last_played_at IS NOT NULL)
            OR EXISTS(SELECT 1 FROM apps WHERE last_played_at IS NOT NULL)
        LIMIT 1
    """.trimIndent()) { it.step() }
}
