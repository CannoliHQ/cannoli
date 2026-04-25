package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal data class Migration(val version: Int, val apply: (SQLiteConnection) -> Unit)

internal object Migrations {
    private val all = listOf(
        Migration(1) { db ->
            db.execSQL("""
                CREATE TABLE platforms (
                    tag TEXT PRIMARY KEY,
                    display_name TEXT,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    last_scan_mtime INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE roms (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT NOT NULL UNIQUE,
                    platform_tag TEXT NOT NULL REFERENCES platforms(tag) ON DELETE CASCADE,
                    display_name TEXT NOT NULL,
                    art_path TEXT,
                    is_subfolder INTEGER NOT NULL DEFAULT 0,
                    disc_paths TEXT,
                    ra_game_id INTEGER,
                    last_played_at INTEGER
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX roms_by_platform ON roms(platform_tag)")
            db.execSQL("CREATE INDEX roms_by_last_played ON roms(last_played_at) WHERE last_played_at IS NOT NULL")

            db.execSQL("""
                CREATE TABLE collections (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    display_name TEXT NOT NULL,
                    parent_id INTEGER REFERENCES collections(id) ON DELETE SET NULL,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    collection_type TEXT NOT NULL DEFAULT 'STANDARD'
                        CHECK (collection_type IN ('STANDARD', 'FAVORITES'))
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX collections_by_type ON collections(collection_type)")

            db.execSQL("""
                CREATE TABLE collection_members (
                    collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
                    rom_id INTEGER NOT NULL REFERENCES roms(id) ON DELETE CASCADE,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (collection_id, rom_id)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX members_by_rom ON collection_members(rom_id)")

            db.execSQL("""
                CREATE TABLE game_overrides (
                    rom_id INTEGER PRIMARY KEY REFERENCES roms(id) ON DELETE CASCADE,
                    core_id TEXT,
                    runner TEXT,
                    app_package TEXT,
                    ra_package TEXT
                )
            """.trimIndent())

        },
    )

    val current: Int = all.maxOf { it.version }

    fun applyFrom(conn: SQLiteConnection, oldVersion: Int) {
        for (migration in all) {
            if (migration.version <= oldVersion) continue
            conn.execSQL("BEGIN")
            try {
                migration.apply(conn)
                conn.execSQL("PRAGMA user_version = ${migration.version}")
                conn.execSQL("COMMIT")
            } catch (t: Throwable) {
                conn.execSQL("ROLLBACK")
                throw MigrationFailure(migration.version, t)
            }
        }
    }
}

class MigrationFailure(val version: Int, cause: Throwable) :
    RuntimeException("schema migration to v$version failed", cause)
