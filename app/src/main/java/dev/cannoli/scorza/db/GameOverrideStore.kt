package dev.cannoli.scorza.db

import dev.cannoli.scorza.config.EmulatorChoice
import dev.cannoli.scorza.config.EmulatorSource

data class GameOverrideEntry(val romId: Long, val displayName: String, val choice: EmulatorChoice)

/**
 * Per-game emulator overrides, keyed by rom_id rather than by path. The scanner keeps rom_id
 * stable through rename, move and auto-organize, and the foreign key cascades on delete, so an
 * override follows its game instead of being orphaned by any of them.
 */
class GameOverrideStore(private val db: CannoliDatabase) {

    fun get(romId: Long): EmulatorChoice? = db.queryOne(
        "SELECT source, core_id, app_package FROM game_overrides WHERE rom_id = ?", romId,
    ) { stmt -> stmt.toChoice() }

    fun put(romId: Long, choice: EmulatorChoice) = db.execute(
        "INSERT OR REPLACE INTO game_overrides (rom_id, source, core_id, app_package) VALUES (?, ?, ?, ?)",
        romId, choice.source.name, choice.coreId.ifEmpty { null }, choice.appPackage,
    )

    fun clear(romId: Long) = db.execute("DELETE FROM game_overrides WHERE rom_id = ?", romId)

    fun forPlatform(tag: String): List<GameOverrideEntry> = db.queryAll(
        """
        SELECT o.rom_id, r.display_name, o.source, o.core_id, o.app_package
        FROM game_overrides o JOIN roms r ON r.id = o.rom_id
        WHERE r.platform_tag = ? ORDER BY r.sort_key
        """.trimIndent(),
        tag.uppercase(),
    ) { stmt ->
        GameOverrideEntry(stmt.getLong(0), stmt.getText(1), stmt.toChoice(offset = 2)!!)
    }

    fun countForPlatform(tag: String): Int = db.queryOne(
        "SELECT COUNT(*) FROM game_overrides o JOIN roms r ON r.id = o.rom_id WHERE r.platform_tag = ?",
        tag.uppercase(),
    ) { it.getInt(0) } ?: 0

    private fun androidx.sqlite.SQLiteStatement.toChoice(offset: Int = 0): EmulatorChoice? {
        val source = runCatching { EmulatorSource.valueOf(getText(offset)) }.getOrNull() ?: return null
        return EmulatorChoice(
            source = source,
            coreId = if (isNull(offset + 1)) "" else getText(offset + 1),
            appPackage = if (isNull(offset + 2)) null else getText(offset + 2),
        )
    }
}
