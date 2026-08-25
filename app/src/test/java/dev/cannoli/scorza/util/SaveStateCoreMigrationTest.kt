package dev.cannoli.scorza.util

import dev.cannoli.scorza.config.CannoliPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Every core on a platform used to write into one file, and resuming handed it to whichever core
 * was mapped at the time. That is what crashed mupen64plus-next: it accepts a state on a version
 * prefix and the ROM's MD5, and neither says who wrote it.
 *
 * States are keyed by core now, which leaves the ones a v1 install already has. Nothing records
 * their writer, so they are adopted by the core the game runs on today.
 */
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

    /**
     * The reason the caller resolves overrides rather than the platform mapping alone. Sending an
     * overridden game's state to the platform's core moves a working state away from the only core
     * that can load it, which is a regression rather than a missed rescue.
     */
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

    // A one-time upgrade, so it runs once. Without the marker it would walk every boot forever.
    @Test fun `it runs once`() {
        val p = root()
        state(p, "N64", "Mario 64", "Mario 64.state")

        assertEquals(1, SaveStateCoreMigration.run(p) { _, _ -> "core_a" }.files)
        state(p, "N64", "Mario 64", "Mario 64.state2")
        assertEquals(0, SaveStateCoreMigration.run(p) { _, _ -> "core_a" }.files)
        assertTrue("a state arriving later is left alone", File(p.saveStateGameDir("N64", "Mario 64"), "Mario 64.state2").isFile)
    }

    // A platform mapped to a standalone app has no core to key by, and its states are not ours.
    @Test fun `a game with no core is left where it is`() {
        val p = root()
        state(p, "PS2", "Game", "Game.state")

        assertEquals(0, SaveStateCoreMigration.run(p) { _, _ -> null }.files)
        assertTrue(File(p.saveStateGameDir("PS2", "Game"), "Game.state").isFile)
    }

    /**
     * A slot already in the core's folder was written after the change, so it is the newer state.
     * The loose file is what it replaced, and overwriting hands the user back an older save.
     */
    @Test fun `a state the core already has is never overwritten`() {
        val p = root()
        state(p, "N64", "Mario 64", "Mario 64.state", "old")
        File(coreDir(p, "N64", "Mario 64", "core_a"), "Mario 64.state")
            .apply { parentFile?.mkdirs(); writeText("new") }

        SaveStateCoreMigration.run(p) { _, _ -> "core_a" }

        assertEquals("new", File(coreDir(p, "N64", "Mario 64", "core_a"), "Mario 64.state").readText())
        assertTrue(File(p.saveStateGameDir("N64", "Mario 64"), "Mario 64.state").isFile)
    }

    /**
     * Slots run .state, .state1 .. .state9, an auto state carries its own suffix, and RetroArch
     * writes a .png thumbnail beside each. The thumbnail travels with its state: left behind it is
     * orphaned, and the state loses its preview in the picker.
     */
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
