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

    fun presentRommIds(): Set<Int> {
        val romDir = romDirProvider()
        return db.queryAll("SELECT romm_id, relative_path FROM romm_links") {
            it.getInt(0) to it.getText(1)
        }.filter { (_, rel) -> File(romDir, rel).exists() }
            .map { it.first }
            .toSet()
    }
}
