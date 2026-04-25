package dev.cannoli.scorza.library

import androidx.sqlite.execSQL
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import org.json.JSONArray
import java.io.File

class RomLibrary(
    private val cannoliRoot: File,
    private val romDirectory: File,
    private val db: CannoliDatabase,
    private val artwork: ArtworkLookup,
) {
    fun gamesForPlatform(platformTag: String, subfolder: String? = null): List<ListItem> {
        val tag = platformTag.uppercase()
        val roms = romsForPlatform(tag)
        val (matching, nested) = partitionForSubfolder(roms, subfolder)
        val subfolderItems = subfolderItemsFrom(nested, subfolder)
        val romItems = matching.map { ListItem.RomItem(it) }
        return subfolderItems + romItems
    }

    fun gameByPath(absolutePath: String): Rom? {
        val relative = relativizePath(absolutePath) ?: return null
        return queryRom("WHERE path = ?", listOf(relative)).firstOrNull()
    }

    fun gameById(romId: Long): Rom? =
        queryRom("WHERE id = ?", listOf(romId.toString())).firstOrNull()

    fun setRaGameId(romId: Long, raGameId: Int?) {
        val sql = if (raGameId == null) "UPDATE roms SET ra_game_id = NULL WHERE id = ?"
        else "UPDATE roms SET ra_game_id = ? WHERE id = ?"
        db.conn.prepare(sql).use { stmt ->
            if (raGameId == null) {
                stmt.bindLong(1, romId)
            } else {
                stmt.bindLong(1, raGameId.toLong())
                stmt.bindLong(2, romId)
            }
            stmt.step()
        }
    }

    fun deleteRom(romId: Long) {
        db.conn.prepare("DELETE FROM roms WHERE id = ?").use { stmt ->
            stmt.bindLong(1, romId)
            stmt.step()
        }
    }

    fun platformCounts(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        db.conn.prepare("SELECT platform_tag, COUNT(*) FROM roms GROUP BY platform_tag").use { stmt ->
            while (stmt.step()) out[stmt.getText(0)] = stmt.getInt(1)
        }
        return out
    }

    fun knownPlatformTags(): List<String> {
        val out = mutableListOf<String>()
        db.conn.prepare("SELECT tag FROM platforms ORDER BY sort_order, tag").use { stmt ->
            while (stmt.step()) out.add(stmt.getText(0))
        }
        return out
    }

    fun setPlatformOrder(orderedTags: List<String>) {
        db.conn.execSQL("BEGIN")
        try {
            orderedTags.forEachIndexed { index, tag ->
                db.conn.prepare("UPDATE platforms SET sort_order = ? WHERE tag = ?").use { stmt ->
                    stmt.bindLong(1, index.toLong())
                    stmt.bindText(2, tag)
                    stmt.step()
                }
            }
            db.conn.execSQL("COMMIT")
        } catch (t: Throwable) {
            db.conn.execSQL("ROLLBACK")
            throw t
        }
    }

    private fun romsForPlatform(platformTag: String): List<Rom> =
        queryRom("WHERE platform_tag = ? ORDER BY display_name COLLATE NOCASE", listOf(platformTag))

    private fun partitionForSubfolder(
        roms: List<Rom>,
        subfolder: String?,
    ): Pair<List<Rom>, List<Rom>> {
        if (subfolder.isNullOrEmpty()) {
            return roms.partition { !it.relativeFromRoot().contains(File.separatorChar.toString() + File.separator) && it.relativeFromRoot().count { c -> c == File.separatorChar } <= 1 }
                .let { (top, _) -> Pair(top.filter { rom -> !rom.relativeAfterPlatform().contains(File.separatorChar) }, top.filter { rom -> rom.relativeAfterPlatform().contains(File.separatorChar) }) }
        }
        val prefix = "$subfolder${File.separator}"
        val matched = roms.filter { it.relativeAfterPlatform().startsWith(prefix) }
        val (here, deeper) = matched.partition { rom ->
            val tail = rom.relativeAfterPlatform().removePrefix(prefix)
            !tail.contains(File.separatorChar)
        }
        return here to deeper
    }

    private fun subfolderItemsFrom(roms: List<Rom>, subfolder: String?): List<ListItem.SubfolderItem> {
        if (roms.isEmpty()) return emptyList()
        val basePrefix = if (subfolder.isNullOrEmpty()) "" else "$subfolder${File.separator}"
        val seen = linkedSetOf<String>()
        for (rom in roms) {
            val tail = rom.relativeAfterPlatform().removePrefix(basePrefix)
            val firstSeg = tail.substringBefore(File.separator)
            if (firstSeg.isNotEmpty()) seen.add(firstSeg)
        }
        return seen.map { name ->
            ListItem.SubfolderItem(name = name, path = (basePrefix + name))
        }
    }

    private fun queryRom(whereClause: String, args: List<String>): List<Rom> {
        val out = mutableListOf<Rom>()
        val sql = """
            SELECT id, path, platform_tag, display_name, tags, disc_paths, ra_game_id
            FROM roms
            $whereClause
        """.trimIndent()
        db.conn.prepare(sql).use { stmt ->
            args.forEachIndexed { index, value -> stmt.bindText(index + 1, value) }
            while (stmt.step()) {
                val id = stmt.getLong(0)
                val relativePath = stmt.getText(1)
                val platformTag = stmt.getText(2)
                val displayName = stmt.getText(3)
                val tags = if (stmt.isNull(4)) null else stmt.getText(4)
                val discPaths = if (stmt.isNull(5)) null else parseDiscPaths(stmt.getText(5))
                val raGameId = if (stmt.isNull(6)) null else stmt.getLong(6).toInt()
                val absoluteFile = File(romDirectory, relativePath)
                val basename = absoluteFile.nameWithoutExtension
                out.add(
                    Rom(
                        id = id,
                        path = absoluteFile,
                        platformTag = platformTag,
                        displayName = displayName,
                        tags = tags,
                        artFile = artwork.find(platformTag, basename),
                        launchTarget = LaunchTarget.RetroArch,
                        discFiles = discPaths?.map { File(romDirectory, it) },
                        raGameId = raGameId,
                    )
                )
            }
        }
        return out
    }

    private fun parseDiscPaths(json: String): List<String>? {
        val arr = try { JSONArray(json) } catch (_: Throwable) { return null }
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
    }

    private fun Rom.relativeFromRoot(): String =
        path.absolutePath.removePrefix(romDirectory.absolutePath).removePrefix(File.separator)

    private fun Rom.relativeAfterPlatform(): String {
        val rel = relativeFromRoot()
        val platformPrefix = "$platformTag${File.separator}"
        return if (rel.startsWith(platformPrefix, ignoreCase = true)) rel.substring(platformPrefix.length) else rel
    }

    private fun relativizePath(absolutePath: String): String? {
        val romsRoot = romDirectory.absolutePath + File.separator
        if (!absolutePath.startsWith(romsRoot)) return null
        return absolutePath.removePrefix(romsRoot)
    }
}
