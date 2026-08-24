package dev.cannoli.scorza.db

import dev.cannoli.scorza.config.EmulatorChoice
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.di.CannoliPathsProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameOverrideStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun db(name: String): CannoliDatabase {
        val root = File(tmp.root, name).apply { mkdirs() }
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } returns File(root, "Roms").apply { mkdirs() }
        return CannoliDatabase(paths)
    }

    private fun insertRom(db: CannoliDatabase, tag: String, path: String): Long {
        db.execute("INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)", tag, tag)
        return db.executeReturningId(
            "INSERT INTO roms (path, platform_tag, display_name, sort_key, name_normalized) VALUES (?, ?, ?, ?, ?)",
            path, tag, path.substringAfterLast('/'), path.lowercase(), path.lowercase(),
        )
    }

    @Test fun `a core override round trips`() {
        val db = db("ov-core")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "NES", "NES/game.nes")
        store.put(id, EmulatorChoice(EmulatorSource.Embedded, "nestopia_libretro"))
        assertEquals(EmulatorChoice(EmulatorSource.Embedded, "nestopia_libretro"), store.get(id))
    }

    @Test fun `a standalone override round trips`() {
        val db = db("ov-app")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "GBA", "GBA/game.gba")
        store.put(id, EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.fastemulator.gba"))
        assertEquals(
            EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.fastemulator.gba"),
            store.get(id),
        )
    }

    @Test fun `put replaces rather than duplicating`() {
        val db = db("ov-replace")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "NES", "NES/game.nes")
        store.put(id, EmulatorChoice(EmulatorSource.Embedded, "a_libretro"))
        store.put(id, EmulatorChoice(EmulatorSource.Embedded, "b_libretro"))
        assertEquals(EmulatorChoice(EmulatorSource.Embedded, "b_libretro"), store.get(id))
        assertEquals(1, store.countForPlatform("NES"))
    }

    @Test fun `clear removes the override`() {
        val db = db("ov-clear")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "NES", "NES/game.nes")
        store.put(id, EmulatorChoice(EmulatorSource.Embedded, "a_libretro"))
        store.clear(id)
        assertNull(store.get(id))
    }

    // The whole reason per-game overrides moved into the database.
    @Test fun `an override follows its rom through a path change`() {
        val db = db("ov-move")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "PS", "PS/FF7 (Disc 1).chd")
        store.put(id, EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.epsxe.ePSXe"))
        db.execute("UPDATE roms SET path = ? WHERE id = ?", "PS/FF7/FF7 (Disc 1).chd", id)
        assertEquals(
            EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.epsxe.ePSXe"),
            store.get(id),
        )
    }

    @Test fun `deleting the rom cascades the override away`() {
        val db = db("ov-cascade")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "NES", "NES/game.nes")
        store.put(id, EmulatorChoice(EmulatorSource.Embedded, "a_libretro"))
        db.execute("DELETE FROM roms WHERE id = ?", id)
        assertNull(store.get(id))
    }

    // path.startsWith(tagDir) used to make Game Boy list and clear GBA overrides.
    @Test fun `forPlatform does not leak across a tag prefix`() {
        val db = db("ov-prefix")
        val store = GameOverrideStore(db)
        val gb = insertRom(db, "GB", "GB/tetris.gb")
        val gba = insertRom(db, "GBA", "GBA/metroid.gba")
        store.put(gb, EmulatorChoice(EmulatorSource.Embedded, "gambatte_libretro"))
        store.put(gba, EmulatorChoice(EmulatorSource.Embedded, "mgba_libretro"))
        assertEquals(1, store.forPlatform("GB").size)
        assertEquals(gb, store.forPlatform("GB").single().romId)
        assertEquals(1, store.countForPlatform("GBA"))
    }

}
