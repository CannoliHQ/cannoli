package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal object Migrations {
    private data class Migration(val version: Int, val apply: (SQLiteConnection) -> Unit)

    private val all = listOf(
        Migration(1) { db ->
            db.execSQL("""
                CREATE TABLE platforms (
                    tag TEXT PRIMARY KEY,
                    display_name TEXT,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    last_scanned_mtime INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE roms (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT NOT NULL,
                    platform_tag TEXT NOT NULL REFERENCES platforms(tag) ON DELETE RESTRICT,
                    display_name TEXT NOT NULL,
                    tags TEXT,
                    disc_paths TEXT,
                    ra_game_id INTEGER,
                    last_played_at INTEGER,
                    UNIQUE(platform_tag, path)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX roms_by_platform ON roms(platform_tag)")
            db.execSQL("CREATE INDEX roms_by_last_played ON roms(last_played_at) WHERE last_played_at IS NOT NULL")

            db.execSQL("""
                CREATE TABLE apps (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL CHECK (type IN ('TOOL', 'PORT')),
                    display_name TEXT NOT NULL,
                    package_name TEXT NOT NULL,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    last_played_at INTEGER,
                    UNIQUE(type, package_name)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX apps_by_type ON apps(type)")
            db.execSQL("CREATE INDEX apps_by_last_played ON apps(last_played_at) WHERE last_played_at IS NOT NULL")

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
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
                    rom_id INTEGER REFERENCES roms(id) ON DELETE CASCADE,
                    app_id INTEGER REFERENCES apps(id) ON DELETE CASCADE,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    CHECK (
                        (rom_id IS NOT NULL AND app_id IS NULL) OR
                        (rom_id IS NULL AND app_id IS NOT NULL)
                    )
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX collection_members_unique_rom ON collection_members(collection_id, rom_id) WHERE rom_id IS NOT NULL")
            db.execSQL("CREATE UNIQUE INDEX collection_members_unique_app ON collection_members(collection_id, app_id) WHERE app_id IS NOT NULL")
            db.execSQL("CREATE INDEX collection_members_by_rom ON collection_members(rom_id) WHERE rom_id IS NOT NULL")
            db.execSQL("CREATE INDEX collection_members_by_app ON collection_members(app_id) WHERE app_id IS NOT NULL")

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
