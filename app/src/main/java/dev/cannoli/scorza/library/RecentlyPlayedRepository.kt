package dev.cannoli.scorza.library

import dev.cannoli.scorza.db.CannoliDatabase

class RecentlyPlayedRepository(private val db: CannoliDatabase) {
    data class Entry(val ref: LibraryRef, val lastPlayedAt: Long)

    fun recent(limit: Int = 10): List<Entry> {
        val sql = """
            SELECT 'rom' AS kind, id, last_played_at FROM roms WHERE last_played_at IS NOT NULL
            UNION ALL
            SELECT 'app' AS kind, id, last_played_at FROM apps WHERE last_played_at IS NOT NULL
            ORDER BY last_played_at DESC
            LIMIT ?
        """.trimIndent()
        val out = mutableListOf<Entry>()
        db.conn.prepare(sql).use { stmt ->
            stmt.bindLong(1, limit.toLong())
            while (stmt.step()) {
                val kind = stmt.getText(0)
                val id = stmt.getLong(1)
                val ts = stmt.getLong(2)
                val ref: LibraryRef = if (kind == "rom") LibraryRef.Rom(id) else LibraryRef.App(id)
                out.add(Entry(ref, ts))
            }
        }
        return out
    }

    fun hasAny(): Boolean {
        db.conn.prepare("""
            SELECT 1 WHERE EXISTS(SELECT 1 FROM roms WHERE last_played_at IS NOT NULL)
                OR EXISTS(SELECT 1 FROM apps WHERE last_played_at IS NOT NULL)
            LIMIT 1
        """.trimIndent()).use { stmt ->
            return stmt.step()
        }
    }
}
