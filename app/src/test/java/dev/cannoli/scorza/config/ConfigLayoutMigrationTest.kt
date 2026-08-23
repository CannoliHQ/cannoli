package dev.cannoli.scorza.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigLayoutMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    /** A config tree in the pre-Internal layout. */
    private fun legacyTree(root: File) {
        val config = File(root, "Config")
        write(File(config, "cannoli.db"), "db")
        write(File(config, "cannoli.db-wal"), "wal")
        write(File(config, "cannoli.db-shm"), "shm")
        write(File(config, "romm.db"), "romm")
        write(File(config, "Cache/.game_cache"), "cache")
        write(File(config, "State/recently_played.txt"), "recent")
        write(File(config, "Assets/cannoli/font.ttf"), "font")
        write(File(config, "RetroAchievements/ra_game_ids.txt"), "ids")
        write(File(config, "RetroArch/retroarch.cfg"), "base")
        write(File(config, "RetroArch/custom.cfg"), "mine")
        write(File(config, "RetroArch/Nestopia/Nestopia.opt"), "opt")
        write(File(config, "Overrides/global.ini"), "[shortcuts]")
        write(File(config, "Overrides/global.cfg"), "layer")
        write(File(config, "arcade_map.txt"), "map")
        write(File(config, "ignore_extensions_roms.txt"), "exts")
        write(File(config, "ignore_files_roms.txt"), "files")
        write(File(config, "settings.json"), "{}")
    }

    @Test
    fun `every legacy path lands at its new home with its content`() {
        val root = tmp.newFolder()
        legacyTree(root)
        val paths = CannoliPaths(root)

        ConfigLayoutMigration.run(root)

        assertEquals("db", paths.database.readText())
        assertEquals("wal", File(paths.configInternal, "cannoli.db-wal").readText())
        assertEquals("shm", File(paths.configInternal, "cannoli.db-shm").readText())
        assertEquals("romm", paths.rommDatabase.readText())
        assertEquals("cache", File(paths.configCache, ".game_cache").readText())
        assertEquals("recent", paths.recentlyPlayedFile.readText())
        assertEquals("font", paths.cannoliFont.readText())
        assertEquals("ids", paths.raGameIdsFile.readText())
        assertEquals("base", paths.retroArchCfg.readText())
        assertEquals("opt", File(paths.configRetroArch, "Nestopia/Nestopia.opt").readText())
        assertEquals("map", paths.arcadeMapFile.readText())
        assertEquals("exts", paths.ignoreExtensionsRoms.readText())
        assertEquals("files", paths.ignoreFilesRoms.readText())
    }

    // custom.cfg has to leave RetroArch/ before the rest of that directory moves inward, or it
    // travels with it and ends up buried again.
    @Test
    fun `custom cfg is lifted to the top of Config, not carried into Internal`() {
        val root = tmp.newFolder()
        legacyTree(root)
        val paths = CannoliPaths(root)

        ConfigLayoutMigration.run(root)

        assertEquals("mine", paths.customCfg.readText())
        assertEquals(File(root, "Config/custom.cfg"), paths.customCfg)
        assertFalse(File(paths.configRetroArch, "custom.cfg").exists())
    }

    // The shortcut chords were one letter from the unrelated override layer beside them.
    @Test
    fun `shortcuts leave Overrides while the global override layer stays`() {
        val root = tmp.newFolder()
        legacyTree(root)
        val paths = CannoliPaths(root)

        ConfigLayoutMigration.run(root)

        assertEquals("[shortcuts]", paths.shortcutsIni.readText())
        assertFalse(File(root, "Config/Overrides/global.ini").exists())
        assertEquals("layer", paths.globalOverrideCfg.readText())
    }

    @Test
    fun `files that stay put are untouched`() {
        val root = tmp.newFolder()
        legacyTree(root)

        ConfigLayoutMigration.run(root)

        assertEquals("{}", CannoliPaths(root).settingsJson.readText())
    }

    @Test
    fun `a second run moves nothing and changes nothing`() {
        val root = tmp.newFolder()
        legacyTree(root)
        val paths = CannoliPaths(root)

        val first = ConfigLayoutMigration.run(root)
        val second = ConfigLayoutMigration.run(root)

        assertTrue("first run should move something", first > 0)
        assertEquals(0, second)
        assertEquals("db", paths.database.readText())
        assertEquals("mine", paths.customCfg.readText())
    }

    // The interrupted case: a previous run moved some entries and died. The next boot has to
    // finish the job rather than skip it because a destination already exists.
    @Test
    fun `a half-migrated tree is completed by the next run`() {
        val root = tmp.newFolder()
        legacyTree(root)
        val paths = CannoliPaths(root)
        // Pretend the database moved and the process died before anything else did.
        paths.configInternal.mkdirs()
        File(root, "Config/cannoli.db").renameTo(paths.database)

        ConfigLayoutMigration.run(root)

        assertEquals("db", paths.database.readText())
        assertEquals("map", paths.arcadeMapFile.readText())
        assertEquals("mine", paths.customCfg.readText())
    }

    @Test
    fun `an existing destination is never overwritten`() {
        val root = tmp.newFolder()
        legacyTree(root)
        val paths = CannoliPaths(root)
        write(paths.customCfg, "already here")

        ConfigLayoutMigration.run(root)

        assertEquals("already here", paths.customCfg.readText())
    }

    // The databases gate their open on runOnce rather than on boot ordering, so it has to stay
    // cheap and it has to actually migrate the first time it sees a root.
    @Test
    fun `runOnce migrates the first time and is inert after`() {
        val root = tmp.newFolder()
        legacyTree(root)
        val paths = CannoliPaths(root)

        ConfigLayoutMigration.runOnce(root)
        assertEquals("db", paths.database.readText())

        // A second call must not move anything, including a file the caller put back at an old path.
        write(File(root, "Config/arcade_map.txt"), "put back")
        ConfigLayoutMigration.runOnce(root)
        assertTrue(File(root, "Config/arcade_map.txt").isFile)
    }

    @Test
    fun `a fresh install with no config tree is a no-op`() {
        val root = tmp.newFolder()

        assertEquals(0, ConfigLayoutMigration.run(root))
    }

    @Test
    fun `a tree already on the new layout is a no-op`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root)
        write(paths.database, "db")
        write(paths.customCfg, "mine")
        write(paths.arcadeMapFile, "map")

        assertEquals(0, ConfigLayoutMigration.run(root))
        assertEquals("db", paths.database.readText())
    }
}
