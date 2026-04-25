package dev.cannoli.scorza.library

import androidx.sqlite.SQLiteStatement
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.db.query
import dev.cannoli.scorza.db.transaction
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import org.json.JSONArray
import java.io.File

class RomLibrary(
    private val romDirectory: File,
    private val db: CannoliDatabase,
    private val artwork: ArtworkLookup,
) {
    fun gamesForPlatform(platformTag: String, subfolder: String? = null): List<ListItem> {
        val tag = platformTag.uppercase()
        val roms = romsForPlatform(tag)
        val (matching, nested) = partitionForSubfolder(roms, subfolder)
        return subfolderItemsFrom(nested, subfolder) + matching.map { ListItem.RomItem(it) }
    }

    fun gameByPath(absolutePath: String): Rom? {
        val relative = relativizePath(absolutePath) ?: return null
        return queryRom("WHERE path = ?", relative).firstOrNull()
    }

    fun gameById(romId: Long): Rom? =
        queryRom("WHERE id = ?", romId).firstOrNull()

    fun setRaGameId(romId: Long, raGameId: Int?) {
        if (raGameId == null) {
            db.conn.execute("UPDATE roms SET ra_game_id = NULL WHERE id = ?", romId)
        } else {
            db.conn.execute("UPDATE roms SET ra_game_id = ? WHERE id = ?", raGameId, romId)
        }
    }

    fun updateRomPath(romId: Long, newRelativePath: String) = db.conn.execute(
        "UPDATE roms SET path = ? WHERE id = ?",
        newRelativePath, romId,
    )

    fun updateRomPathsUnderPrefix(platformTag: String, oldPrefix: String, newPrefix: String) = db.conn.execute(
        """
        UPDATE roms
        SET path = ? || substr(path, ?)
        WHERE platform_tag = ? AND path LIKE ?
        """.trimIndent(),
        newPrefix, (oldPrefix.length + 1).toLong(), platformTag.uppercase(), "$oldPrefix%",
    )

    fun deleteRom(romId: Long) = db.conn.execute("DELETE FROM roms WHERE id = ?", romId)

    fun platformCounts(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        db.conn.query("SELECT platform_tag, COUNT(*) FROM roms GROUP BY platform_tag") { stmt ->
            while (stmt.step()) out[stmt.getText(0)] = stmt.getInt(1)
        }
        return out
    }

    fun knownPlatformTags(): List<String> {
        val out = mutableListOf<String>()
        db.conn.query("SELECT tag FROM platforms ORDER BY sort_order, tag") { stmt ->
            while (stmt.step()) out.add(stmt.getText(0))
        }
        return out
    }

    fun setPlatformOrder(orderedTags: List<String>) = db.conn.transaction {
        orderedTags.forEachIndexed { index, tag ->
            db.conn.execute("UPDATE platforms SET sort_order = ? WHERE tag = ?", index.toLong(), tag)
        }
    }

    private fun romsForPlatform(platformTag: String): List<Rom> =
        queryRom("WHERE platform_tag = ? ORDER BY display_name COLLATE NOCASE", platformTag)

    /** When a subfolder is selected, return roms inside it (here) and roms in deeper subdirs (deeper).
     *  When no subfolder is selected, return roms at the platform root (here) and roms anywhere
     *  inside subfolders (deeper) so the deeper set drives top-level subfolder pseudo-items. */
    private fun partitionForSubfolder(roms: List<Rom>, subfolder: String?): Pair<List<Rom>, List<Rom>> {
        val sep = File.separatorChar
        val basePrefix = if (subfolder.isNullOrEmpty()) "" else "$subfolder$sep"
        val matched = if (basePrefix.isEmpty()) roms
        else roms.filter { it.relativeAfterPlatform().startsWith(basePrefix) }
        return matched.partition { rom ->
            !rom.relativeAfterPlatform().removePrefix(basePrefix).contains(sep)
        }
    }

    private fun subfolderItemsFrom(roms: List<Rom>, subfolder: String?): List<ListItem.SubfolderItem> {
        if (roms.isEmpty()) return emptyList()
        val basePrefix = if (subfolder.isNullOrEmpty()) "" else "$subfolder${File.separator}"
        val seen = linkedSetOf<String>()
        for (rom in roms) {
            val firstSeg = rom.relativeAfterPlatform().removePrefix(basePrefix).substringBefore(File.separator)
            if (firstSeg.isNotEmpty()) seen.add(firstSeg)
        }
        return seen.map { ListItem.SubfolderItem(name = it, path = basePrefix + it) }
    }

    private fun queryRom(whereClause: String, vararg args: Any?): List<Rom> {
        val out = mutableListOf<Rom>()
        db.conn.query(
            """
            SELECT id, path, platform_tag, display_name, tags, disc_paths, ra_game_id
            FROM roms
            $whereClause
            """.trimIndent()
        ) { stmt ->
            args.forEachIndexed { index, value ->
                val pos = index + 1
                when (value) {
                    is Long -> stmt.bindLong(pos, value)
                    is String -> stmt.bindText(pos, value)
                    else -> error("unsupported queryRom arg ${value?.let { it::class.java.name }}")
                }
            }
            while (stmt.step()) out.add(rowToRom(stmt))
        }
        return out
    }

    private fun rowToRom(stmt: SQLiteStatement): Rom {
        val id = stmt.getLong(0)
        val relativePath = stmt.getText(1)
        val platformTag = stmt.getText(2)
        val displayName = stmt.getText(3)
        val tags = if (stmt.isNull(4)) null else stmt.getText(4)
        val discPaths = if (stmt.isNull(5)) null else parseDiscPaths(stmt.getText(5))
        val raGameId = if (stmt.isNull(6)) null else stmt.getLong(6).toInt()
        val absoluteFile = File(romDirectory, relativePath)
        return Rom(
            id = id,
            path = absoluteFile,
            platformTag = platformTag,
            displayName = displayName,
            tags = tags,
            artFile = artwork.find(platformTag, absoluteFile.nameWithoutExtension),
            launchTarget = LaunchTarget.RetroArch,
            discFiles = discPaths?.map { File(romDirectory, it) },
            raGameId = raGameId,
        )
    }

    private fun parseDiscPaths(json: String): List<String>? {
        val arr = try { JSONArray(json) } catch (_: Throwable) { return null }
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
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
}
