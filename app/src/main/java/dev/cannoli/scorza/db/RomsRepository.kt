package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteStatement
import dev.cannoli.scorza.model.GameSearchQuery
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.NaturalSort
import dev.cannoli.scorza.util.RomNaming
import dev.cannoli.scorza.util.TextNormalizer
import java.io.File

class RomsRepository(
    private val pathsProvider: CannoliPathsProvider,
    private val db: CannoliDatabase,
    private val artwork: ArtworkLookup,
) {
    private val romDirectory: File get() = pathsProvider.romDir

    fun gamesForPlatform(platformTag: String, subfolder: String? = null): List<ListItem> {
        val tag = platformTag.uppercase()
        val roms = romsForPlatform(tag)
        val (matching, nested) = partitionForSubfolder(roms, subfolder)
        return subfolderItemsFrom(nested, subfolder) + matching.map { ListItem.RomItem(it) }
    }

    fun gameByPath(absolutePath: String): Rom? {
        val relative = relativizePath(absolutePath) ?: return null
        return db.queryOne("$BASE_SELECT WHERE path = ?", relative, mapper = ::rowToRom)
    }

    fun gameById(romId: Long): Rom? = db.queryOne(
        "$BASE_SELECT WHERE id = ?", romId, mapper = ::rowToRom,
    )

    fun setRaGameId(romId: Long, raGameId: Int?) {
        if (raGameId == null) {
            db.execute("UPDATE roms SET ra_game_id = NULL WHERE id = ?", romId)
        } else {
            db.execute("UPDATE roms SET ra_game_id = ? WHERE id = ?", raGameId, romId)
        }
    }

    fun setRaCachedGameId(romId: Long, gameId: Int?) {
        if (gameId == null) {
            db.execute("UPDATE roms SET ra_cached_game_id = NULL WHERE id = ?", romId)
        } else {
            db.execute("UPDATE roms SET ra_cached_game_id = ? WHERE id = ?", gameId, romId)
        }
    }

    fun updateRomPath(romId: Long, newRelativePath: String) = db.execute(
        "UPDATE roms SET path = ? WHERE id = ?",
        newRelativePath, romId,
    )

    // Renames a rom in place: updates the path plus every name-derived column the same way the
    // scanner would, so the list re-sorts to the new name immediately instead of waiting for the
    // file-watcher rescan (which then finds nothing changed and stays a no-op).
    fun renameRom(romId: Long, newRelativePath: String, newBaseName: String) {
        val (displayName, tags) = RomNaming.splitNameAndTags(newBaseName)
        db.execute(
            "UPDATE roms SET path = ?, display_name = ?, sort_key = ?, tags = ?, name_normalized = ? WHERE id = ?",
            newRelativePath,
            displayName,
            NaturalSort.toSortKey(displayName),
            tags,
            TextNormalizer.normalize(displayName),
            romId,
        )
    }

    fun updateRomPathsUnderPrefix(platformTag: String, oldPrefix: String, newPrefix: String) = db.execute(
        """
        UPDATE roms
        SET path = ? || substr(path, ?)
        WHERE platform_tag = ? AND path LIKE ?
        """.trimIndent(),
        newPrefix, (oldPrefix.length + 1).toLong(), platformTag.uppercase(), "$oldPrefix%",
    )

    fun deleteRom(romId: Long) = db.execute("DELETE FROM roms WHERE id = ?", romId)

    fun deleteRomsUnderPrefix(platformTag: String, dirPrefix: String) = db.execute(
        "DELETE FROM roms WHERE platform_tag = ? AND path LIKE ?",
        platformTag.uppercase(), "$dirPrefix${File.separator}%",
    )

    fun platformCounts(): Map<String, Int> = db.queryAll(
        "SELECT platform_tag, COUNT(*) FROM roms GROUP BY platform_tag",
    ) { it.getText(0) to it.getInt(1) }.toMap()

    fun knownPlatformTags(): List<String> = db.queryAll(
        "SELECT tag FROM platforms ORDER BY sort_order, display_name COLLATE NOCASE",
    ) { it.getText(0) }

    /** Lowercased filenames currently indexed for [platformTag], for RomM on-device matching. */
    fun presentFileNames(platformTag: String): Set<String> = db.queryAll(
        "SELECT path FROM roms WHERE platform_tag = ?", platformTag.uppercase(),
    ) { File(it.getText(0)).name.lowercase() }.toSet()

    fun allRelativePaths(): List<String> = db.queryAll("SELECT path FROM roms") { it.getText(0) }

    fun searchAllGames(query: GameSearchQuery): List<Rom> {
        val term = TextNormalizer.normalize(query.text)
        if (term.isEmpty()) return emptyList()
        return db.queryAll(
            "$BASE_SELECT WHERE name_normalized LIKE ? ORDER BY display_name COLLATE NOCASE",
            "%$term%",
            mapper = ::rowToRom,
        )
    }

    fun setPlatformOrder(orderedTags: List<String>) = db.transaction { conn ->
        orderedTags.forEachIndexed { index, tag ->
            conn.execute("UPDATE platforms SET sort_order = ? WHERE tag = ?", index.toLong(), tag)
        }
    }

    fun allRomsForPlatform(platformTag: String): List<Rom> = romsForPlatform(platformTag.uppercase())

    private fun romsForPlatform(platformTag: String): List<Rom> = db.queryAll(
        "$BASE_SELECT WHERE platform_tag = ? ORDER BY sort_key",
        platformTag, mapper = ::rowToRom,
    )

    /** When a subfolder is selected, return roms inside it (here) and roms in deeper subdirs (deeper).
     *  When no subfolder is selected, return roms at the platform root (here) and roms anywhere
     *  inside subfolders (deeper) so the deeper set drives top-level subfolder pseudo-items. */
    private fun partitionForSubfolder(roms: List<Rom>, subfolder: String?): Pair<List<Rom>, List<Rom>> {
        val sep = File.separatorChar
        val basePrefix = if (subfolder.isNullOrEmpty()) "" else "$subfolder$sep"
        val matched = if (basePrefix.isEmpty()) roms
        else roms.filter { it.relativeAfterPlatform().startsWith(basePrefix) }
        return matched.partition { rom ->
            val remaining = rom.relativeAfterPlatform().removePrefix(basePrefix)
            !remaining.contains(sep) || isSelfContainedBundle(remaining, sep)
        }
    }

    /** A rom whose remaining path is `<folder>/<folder>.<ext>` is a self-contained bundle
     *  (organizer-created or user-organized). Show it at the current level, not behind a
     *  subfolder row. */
    private fun isSelfContainedBundle(remaining: String, sep: Char): Boolean {
        val sepIndex = remaining.indexOf(sep)
        if (sepIndex < 0 || remaining.indexOf(sep, sepIndex + 1) >= 0) return false
        val folder = remaining.substring(0, sepIndex)
        val file = remaining.substring(sepIndex + 1)
        val dot = file.lastIndexOf('.')
        if (dot <= 0) return false
        return file.substring(0, dot) == folder
    }

    private fun subfolderItemsFrom(roms: List<Rom>, subfolder: String?): List<ListItem.SubfolderItem> {
        if (roms.isEmpty()) return emptyList()
        val basePrefix = if (subfolder.isNullOrEmpty()) "" else "$subfolder${File.separator}"
        val seen = linkedSetOf<String>()
        for (rom in roms) {
            val firstSeg = rom.relativeAfterPlatform().removePrefix(basePrefix).substringBefore(File.separator)
            if (firstSeg.isNotEmpty()) seen.add(firstSeg)
        }
        return seen.sortedWith(NaturalSort).map { ListItem.SubfolderItem(name = it, path = basePrefix + it) }
    }

    private fun rowToRom(stmt: SQLiteStatement): Rom {
        val relativePath = stmt.getText(1)
        val platformTag = stmt.getText(2)
        val absoluteFile = File(romDirectory, relativePath)
        return Rom(
            id = stmt.getLong(0),
            path = absoluteFile,
            platformTag = platformTag,
            displayName = stmt.getText(3),
            tags = if (stmt.isNull(4)) null else stmt.getText(4),
            artFile = artwork.find(platformTag, absoluteFile),
            launchTarget = LaunchTarget.RetroArch,
            raGameId = if (stmt.isNull(5)) null else stmt.getLong(5).toInt(),
            lastPlayedAt = if (stmt.isNull(6)) null else stmt.getLong(6),
            raCachedGameId = if (stmt.isNull(7)) null else stmt.getLong(7).toInt(),
        )
    }

    private fun Rom.relativeAfterPlatform(): String {
        val rel = path.absolutePath.removePrefix(romDirectory.absolutePath).removePrefix(File.separator)
        val platformPrefix = "$platformTag${File.separator}"
        return if (rel.startsWith(platformPrefix, ignoreCase = true)) rel.substring(platformPrefix.length) else rel
    }

    private fun relativizePath(absolutePath: String): String? {
        val romsRoot = romDirectory.absolutePath + File.separator
        if (!absolutePath.startsWith(romsRoot)) return null
        return absolutePath.removePrefix(romsRoot)
    }

    private companion object {
        const val BASE_SELECT = "SELECT id, path, platform_tag, display_name, tags, ra_game_id, last_played_at, ra_cached_game_id FROM roms"
    }
}
