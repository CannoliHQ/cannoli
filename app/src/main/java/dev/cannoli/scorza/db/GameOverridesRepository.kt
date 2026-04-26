package dev.cannoli.scorza.db

class GameOverridesRepository(private val db: CannoliDatabase) {
    data class Override(
        val coreId: String? = null,
        val runner: String? = null,
        val appPackage: String? = null,
        val raPackage: String? = null,
    )

    fun get(romId: Long): Override? = db.queryOne(
        "SELECT core_id, runner, app_package, ra_package FROM game_overrides WHERE rom_id = ?",
        romId,
    ) { stmt ->
        Override(
            coreId = if (stmt.isNull(0)) null else stmt.getText(0),
            runner = if (stmt.isNull(1)) null else stmt.getText(1),
            appPackage = if (stmt.isNull(2)) null else stmt.getText(2),
            raPackage = if (stmt.isNull(3)) null else stmt.getText(3),
        )
    }
}
