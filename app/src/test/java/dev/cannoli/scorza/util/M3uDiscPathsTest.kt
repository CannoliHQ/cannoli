package dev.cannoli.scorza.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class M3uDiscPathsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `parseM3uDiscPaths returns ordered absolute disc paths`() {
        val dir = tmp.newFolder()
        val m3u = File(dir, "game.m3u").apply {
            writeText("# comment\ngame (Disc 1).iso\ngame (Disc 2).iso\n")
        }
        assertEquals(
            listOf(File(dir, "game (Disc 1).iso").absolutePath, File(dir, "game (Disc 2).iso").absolutePath),
            parseM3uDiscPaths(m3u),
        )
    }

    @Test
    fun `parseM3uDiscPaths skips blank lines`() {
        val dir = tmp.newFolder()
        val m3u = File(dir, "game.m3u").apply {
            writeText("game (Disc 1).iso\n\n  \ngame (Disc 2).iso\n")
        }
        assertEquals(
            listOf(File(dir, "game (Disc 1).iso").absolutePath, File(dir, "game (Disc 2).iso").absolutePath),
            parseM3uDiscPaths(m3u),
        )
    }

    @Test
    fun `parseM3uDiscPaths returns empty list for unreadable file`() {
        val missing = File(tmp.newFolder(), "missing.m3u")
        assertEquals(emptyList<String>(), parseM3uDiscPaths(missing))
    }
}
