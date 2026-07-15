package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import dev.cannoli.scorza.util.TextNormalizer

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
                    sort_key TEXT NOT NULL DEFAULT '',
                    tags TEXT,
                    disc_paths TEXT,
                    ra_game_id INTEGER,
                    last_played_at INTEGER,
                    UNIQUE(platform_tag, path)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX roms_by_platform_sort ON roms(platform_tag, sort_key)")
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
        Migration(2) { db ->
            db.execSQL("""
                UPDATE collection_members
                SET sort_order = (
                    SELECT COUNT(*) FROM collection_members AS earlier
                    WHERE earlier.collection_id = collection_members.collection_id
                      AND earlier.id < collection_members.id
                )
                WHERE collection_id IN (
                    SELECT collection_id FROM collection_members
                    GROUP BY collection_id
                    HAVING MAX(sort_order) = 0 AND COUNT(*) > 1
                )
            """.trimIndent())
        },
        Migration(3) { db ->
            db.execSQL("""
                CREATE TABLE game_overrides_new (
                    rom_id INTEGER PRIMARY KEY REFERENCES roms(id) ON DELETE CASCADE,
                    core_id TEXT,
                    runner TEXT,
                    app_package TEXT
                )
            """.trimIndent())
            db.execSQL("""
                INSERT INTO game_overrides_new (rom_id, core_id, runner, app_package)
                SELECT rom_id, core_id, runner, app_package FROM game_overrides
            """.trimIndent())
            db.execSQL("DROP TABLE game_overrides")
            db.execSQL("ALTER TABLE game_overrides_new RENAME TO game_overrides")
        },
        Migration(4) { db ->
            db.execSQL("ALTER TABLE roms DROP COLUMN disc_paths")
            db.execSQL("UPDATE platforms SET last_scanned_mtime = 0")
        },
        Migration(5) { db ->
            db.execSQL("ALTER TABLE platforms DROP COLUMN last_scanned_mtime")
        },
        Migration(6) { db ->
            db.execSQL("""
                CREATE TABLE romm_links (
                    romm_id INTEGER PRIMARY KEY,
                    relative_path TEXT NOT NULL,
                    link_source TEXT NOT NULL CHECK (link_source IN ('download', 'manual')),
                    created_at INTEGER
                )
            """.trimIndent())
        },
        Migration(7) { db ->
            db.execSQL("ALTER TABLE roms ADD COLUMN name_normalized TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX roms_by_name_normalized ON roms(name_normalized)")
            val rows = db.queryAll("SELECT id, display_name FROM roms") { it.getLong(0) to it.getText(1) }
            for ((id, name) in rows) {
                db.execute("UPDATE roms SET name_normalized = ? WHERE id = ?", TextNormalizer.normalize(name), id)
            }
        },
        Migration(8) { db ->
            db.execSQL("ALTER TABLE roms ADD COLUMN ra_cached_game_id INTEGER")
        },
        Migration(9) { db ->
            db.execSQL("""
                CREATE TABLE save_sync (
                    game_key TEXT NOT NULL,
                    slot TEXT NOT NULL,
                    romm_rom_id INTEGER NOT NULL,
                    romm_save_id INTEGER,
                    last_synced_at TEXT,
                    last_uploaded_hash TEXT,
                    local_content_hash TEXT,
                    server_updated_at TEXT,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (game_key, slot)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX save_sync_by_game ON save_sync(game_key)")
            db.execSQL("""
                CREATE TABLE save_slot_active (
                    game_key TEXT PRIMARY KEY,
                    active_slot TEXT NOT NULL
                )
            """.trimIndent())
        },
        Migration(10) { db ->
            db.execSQL(
                """
                CREATE TABLE sync_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    game_key TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    detail TEXT,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX sync_history_by_time ON sync_history(created_at DESC, id DESC)")
            db.execSQL(
                """
                CREATE TABLE pending_conflicts (
                    game_key TEXT PRIMARY KEY,
                    rom_id INTEGER NOT NULL,
                    display_name TEXT NOT NULL,
                    server_save_id INTEGER,
                    server_content_hash TEXT,
                    server_updated_at TEXT,
                    detected_at INTEGER NOT NULL,
                    dismissed_hash TEXT
                )
                """.trimIndent()
            )
        },
        Migration(11) { db ->
            db.execSQL(
                """
                CREATE TABLE restore_promotions (
                    game_key TEXT NOT NULL,
                    slot TEXT NOT NULL,
                    target_hash TEXT NOT NULL,
                    base_head TEXT,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (game_key, slot)
                )
                """.trimIndent()
            )
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
