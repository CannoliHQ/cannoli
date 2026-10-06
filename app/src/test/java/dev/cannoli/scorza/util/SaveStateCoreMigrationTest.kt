package dev.cannoli.scorza.util

import dev.cannoli.scorza.config.CannoliPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SaveStateCoreMigrationTest {

    private fun root(): CannoliPaths =
        CannoliPaths(Files.createTempDirectory("states").toFile())

    private fun state(paths: CannoliPaths, tag: String, game: String, name: String, body: String = "s") =
        File(paths.saveStateGameDir(tag, game), name).apply { parentFile?.mkdirs(); writeText(body) }

    private fun coreDir(paths: CannoliPaths, tag: String, game: String, core: String) =
        paths.saveStateDir(tag, game, core)

    @Test fun `loose states move into the core the game runs on`() {
        val p = root()
        state(p, "N64", "Mario 64", "Mario 64.state")
        state(p, "N64", "Mario 64", "Mario 64.state1")

        val r = SaveStateCoreMigration.run(p) { _, _ -> "mupen64plus_next_gles3" }

        assertEquals(1, r.games)
        assertEquals(2, r.files)
        assertTrue(File(coreDir(p, "N64", "Mario 64", "mupen64plus_next_gles3"), "Mario 64.state").isFile)
        assertFalse(File(p.saveStateGameDir("N64", "Mario 64"), "Mario 64.state").exists())
    }

    @Test fun `each game is adopted by its own answer, not one per platform`() {
        val p = root()
        state(p, "N64", "Mario 64", "Mario 64.state")
        state(p, "N64", "Mario Golf", "Mario Golf.state")

        SaveStateCoreMigration.run(p) { _, game ->
            if (game == "Mario Golf") "parallel_n64_libretro" else "mupen64plus_next_gles3"
        }

        assertTrue(File(coreDir(p, "N64", "Mario Golf", "parallel_n64_libretro"), "Mario Golf.state").isFile)
        assertTrue(File(coreDir(p, "N64", "Mario 64", "mupen64plus_next_gles3"), "Mario 64.state").isFile)
    }

    @Test fun `it runs once`() {
        val p = root()
        state(p, "N64", "Mario 64", "Mario 64.state")

        assertEquals(1, SaveStateCoreMigration.run(p) { _, _ -> "core_a" }.files)
        state(p, "N64", "Mario 64", "Mario 64.state2")
        assertEquals(0, SaveStateCoreMigration.run(p) { _, _ -> "core_a" }.files)
        assertTrue("a state arriving later is left alone", File(p.saveStateGameDir("N64", "Mario 64"), "Mario 64.state2").isFile)
    }

    @Test fun `a game with no core is left where it is`() {
        val p = root()
        state(p, "PS2", "Game", "Game.state")

        assertEquals(0, SaveStateCoreMigration.run(p) { _, _ -> null }.files)
        assertTrue(File(p.saveStateGameDir("PS2", "Game"), "Game.state").isFile)
    }

    @Test fun `a state the core already has is never overwritten`() {
        val p = root()
        state(p, "N64", "Mario 64", "Mario 64.state", "old")
        File(coreDir(p, "N64", "Mario 64", "core_a"), "Mario 64.state")
            .apply { parentFile?.mkdirs(); writeText("new") }

        SaveStateCoreMigration.run(p) { _, _ -> "core_a" }

        assertEquals("new", File(coreDir(p, "N64", "Mario 64", "core_a"), "Mario 64.state").readText())
        assertTrue(File(p.saveStateGameDir("N64", "Mario 64"), "Mario 64.state").isFile)
    }

    @Test fun `every slot suffix is adopted, thumbnails included`() {
        val p = root()
        listOf("G.state", "G.state1", "G.state9", "G.state.auto", "G.state.png", "G.state.auto.png")
            .forEach { state(p, "NES", "G", it) }

        val r = SaveStateCoreMigration.run(p) { _, _ -> "core_a" }

        assertEquals(6, r.files)
        assertTrue(File(coreDir(p, "NES", "G", "core_a"), "G.state.png").isFile)
    }

    @Test fun `an install with no states is not an error`() {
        assertEquals(0, SaveStateCoreMigration.run(root()) { _, _ -> "core_a" }.files)
    }
}
