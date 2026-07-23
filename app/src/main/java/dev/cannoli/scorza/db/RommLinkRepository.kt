package dev.cannoli.scorza.db

import java.io.File

class RommLinkRepository(
    private val db: CannoliDatabase,
    private val romDirProvider: () -> File,
) {
    fun upsertLink(rommId: Int, relativePath: String, source: String) = db.execute(
        "INSERT OR REPLACE INTO romm_links (romm_id, relative_path, link_source, created_at) VALUES (?, ?, ?, ?)",
        rommId, relativePath, source, System.currentTimeMillis(),
    )

    fun removeLink(rommId: Int) = db.execute("DELETE FROM romm_links WHERE romm_id = ?", rommId)

    fun rommIdForPath(relativePath: String): Int? = db.queryOne(
        "SELECT romm_id FROM romm_links WHERE relative_path = ?", relativePath,
    ) { it.getInt(0) }

    fun relativePathFor(rommId: Int): String? = db.queryOne(
        "SELECT relative_path FROM romm_links WHERE romm_id = ?", rommId,
    ) { it.getText(0) }

    fun allRelativePaths(): List<String> = db.queryAll("SELECT relative_path FROM romm_links") { it.getText(0) }

    fun presentRommIds(): Set<Int> {
        val romDir = romDirProvider()
        return db.queryAll("SELECT romm_id, relative_path FROM romm_links") {
            it.getInt(0) to it.getText(1)
        }.filter { (_, rel) -> File(romDir, rel).exists() }
            .map { it.first }
            .toSet()
    }
}
