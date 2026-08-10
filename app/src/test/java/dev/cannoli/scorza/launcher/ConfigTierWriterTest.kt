package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.CannoliPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val BANNER =
    "# DO NOT EDIT - Cannoli writes this from your menu choices. Your own keys go in custom.cfg"

class ConfigTierWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writer(): Pair<ConfigTierWriter, CannoliPaths> {
        val paths = CannoliPaths(tmp.newFolder().absolutePath)
        return ConfigTierWriter(paths) to paths
    }

    @Test fun `set on an empty scope creates the file with the banner and one line`() {
        val (writer, paths) = writer()
        writer.set(ConfigScope.Global, "rewind_enable", "true")

        val lines = paths.globalOverrideCfg.readText().lines()
        assertEquals(BANNER, lines[0])
        assertEquals("rewind_enable = \"true\"", lines[1])
    }

    @Test fun `set on an existing key rewrites only that line`() {
        val (writer, paths) = writer()
        writer.set(ConfigScope.Global, "rewind_enable", "true")
        writer.set(ConfigScope.Global, "video_smooth", "false")

        writer.set(ConfigScope.Global, "rewind_enable", "false")

        val text = paths.globalOverrideCfg.readText()
        assertTrue(text.contains("rewind_enable = \"false\""))
        assertFalse(text.contains("rewind_enable = \"true\""))
        assertTrue(text.contains("video_smooth = \"false\""))
    }

    @Test fun `remove drops the line and leaves the rest`() {
        val (writer, paths) = writer()
        writer.set(ConfigScope.Global, "rewind_enable", "true")
        writer.set(ConfigScope.Global, "video_smooth", "false")

        writer.remove(ConfigScope.Global, "rewind_enable")

        val text = paths.globalOverrideCfg.readText()
        assertFalse(text.contains("rewind_enable"))
        assertTrue(text.contains("video_smooth = \"false\""))
    }

    @Test fun `removing the last key leaves the file at just the banner`() {
        val (writer, paths) = writer()
        writer.set(ConfigScope.Global, "rewind_enable", "true")

        writer.remove(ConfigScope.Global, "rewind_enable")

        assertTrue(paths.globalOverrideCfg.isFile)
        assertEquals(BANNER, paths.globalOverrideCfg.readText().trim())
    }

    @Test fun `remove on a scope with no file is a no-op and does not create one`() {
        val (writer, paths) = writer()

        writer.remove(ConfigScope.Global, "rewind_enable")

        assertFalse(paths.globalOverrideCfg.exists())
    }

    @Test fun `set on a system scope writes to that platform's override file`() {
        val (writer, paths) = writer()
        writer.set(ConfigScope.System("NES"), "rewind_enable", "true")

        assertTrue(paths.systemOverrideCfg("NES").isFile)
        assertFalse(paths.globalOverrideCfg.exists())
    }

    @Test fun `set on a game scope writes to that game's override file`() {
        val (writer, paths) = writer()
        writer.set(ConfigScope.Game("NES", "Super Mario Bros"), "rewind_enable", "true")

        assertTrue(paths.gameOverrideCfg("NES", "Super Mario Bros").isFile)
    }
}
