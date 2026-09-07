package dev.cannoli.scorza.db

import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.sigil.GameId
import dev.cannoli.scorza.sigil.GameIdStatus
import dev.cannoli.scorza.sigil.IdSource
import dev.cannoli.scorza.sigil.SaveUsage
import java.io.File

data class ProbeTarget(val romId: Long, val file: File, val platformTag: String)

class GameIdRepository(
    private val pathsProvider: CannoliPathsProvider,
    private val db: CannoliDatabase,
) {
    fun pending(tags: Collection<String>, limit: Int): List<ProbeTarget> {
        if (tags.isEmpty()) return emptyList()
        val placeholders = tags.joinToString(",") { "?" }
        val args = tags.toMutableList<Any?>().apply { add(limit) }.toTypedArray()
        return db.queryAll(
            "SELECT id, path, platform_tag FROM roms " +
                "WHERE sigil_probe IS NULL AND platform_tag IN ($placeholders) LIMIT ?",
            *args,
        ) { ProbeTarget(it.getLong(0), File(pathsProvider.romDir, it.getText(1)), it.getText(2)) }
    }

    /** Writes the fingerprint whether or not there is an id, so a failure is never retried. */
    fun record(romId: Long, probe: String, id: GameId?) = db.execute(
        "UPDATE roms SET sigil_probe = ?, sigil_title_id = ?, sigil_save_id = ?, " +
            "sigil_raw_serial = ?, sigil_usage = ?, sigil_source = ?, sigil_experimental = ? " +
            "WHERE id = ?",
        probe,
        id?.titleId,
        id?.saveId,
        id?.rawSerial,
        id?.usage?.name,
        id?.source?.name,
        id?.experimental == true,
        romId,
    )

    fun read(romId: Long): GameIdStatus = db.queryOne(
        "SELECT sigil_probe, sigil_title_id, sigil_save_id, sigil_raw_serial, sigil_usage, " +
            "sigil_source, sigil_experimental FROM roms WHERE id = ?",
        romId,
    ) { row ->
        val titleId = if (row.isNull(1)) "" else row.getText(1)
        val usage = if (row.isNull(4)) null else SaveUsage.entries.firstOrNull { it.name == row.getText(4) }
        val source = if (row.isNull(5)) null else IdSource.entries.firstOrNull { it.name == row.getText(5) }
        when {
            row.isNull(0) -> GameIdStatus.Pending
            titleId.isEmpty() || usage == null || source == null -> GameIdStatus.NotFound
            else -> GameIdStatus.Found(
                GameId(
                    titleId = titleId,
                    saveId = if (row.isNull(2)) "" else row.getText(2),
                    rawSerial = if (row.isNull(3)) "" else row.getText(3),
                    usage = usage,
                    source = source,
                    experimental = row.getLong(6) != 0L,
                )
            )
        }
    } ?: GameIdStatus.Pending
}
