package dev.cannoli.scorza.launcher

import dev.cannoli.core.IniParser
import dev.cannoli.core.IniWriter
import dev.cannoli.scorza.model.Rom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuidesKeyMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun guidesRoot() = File(tmp.root, "Guides")
    private fun positionsFile() = File(tmp.root, "Config/State/guide_positions.ini")

    private fun rom(id: Long, tag: String, fileName: String, displayName: String) = Rom(
        id = id,
        path = File("/roms/$tag/$fileName"),
        platformTag = tag,
        displayName = displayName,
    )

    private fun migration(roms: List<Rom>) = GuidesKeyMigration(
        guidesDir = { guidesRoot() },
        positionsFile = { positionsFile() },
        roms = { roms },
    )

    @Test fun `a clean library is a no-op`() {
        migration(emptyList()).migrateIfNeeded()
        assertEquals(0, guidesRoot().listFiles()?.count { it.isDirectory } ?: 0)
    }

    @Test fun `a dir already at the base name is left alone`() {
        val dir = File(guidesRoot(), "NES/Zelda II").apply { mkdirs() }
        File(dir, "manual.pdf").writeText("x")

        migration(listOf(rom(1, "NES", "Zelda II.nes", "Zelda II"))).migrateIfNeeded()

        assertTrue(dir.exists())
        assertTrue(File(dir, "manual.pdf").exists())
    }

    @Test fun `a dir matching exactly one rom's display name is renamed to its base name`() {
        val dir = File(guidesRoot(), "NES/Zelda II - The Adventure of Link").apply { mkdirs() }
        File(dir, "manual.pdf").writeText("x")
        val roms = listOf(
            rom(1, "NES", "Zelda II - The Adventure of Link (USA).nes", "Zelda II - The Adventure of Link"),
        )

        migration(roms).migrateIfNeeded()

        assertFalse(dir.exists())
        val target = File(guidesRoot(), "NES/Zelda II - The Adventure of Link (USA)")
        assertTrue(target.exists())
        assertTrue(File(target, "manual.pdf").exists())
    }

    @Test fun `a dir matching multiple roms' display names is skipped`() {
        val dir = File(guidesRoot(), "NES/Castlevania").apply { mkdirs() }
        File(dir, "manual.pdf").writeText("x")
        val roms = listOf(
            rom(1, "NES", "Castlevania (USA).nes", "Castlevania"),
            rom(2, "NES", "Castlevania (Japan).nes", "Castlevania"),
        )

        migration(roms).migrateIfNeeded()

        assertTrue(dir.exists())
        assertTrue(File(dir, "manual.pdf").exists())
    }

    @Test fun `a dir matching no rom at all is left alone`() {
        val dir = File(guidesRoot(), "NES/Orphaned Game").apply { mkdirs() }
        File(dir, "manual.pdf").writeText("x")

        migration(emptyList()).migrateIfNeeded()

        assertTrue(dir.exists())
        assertTrue(File(dir, "manual.pdf").exists())
    }

    @Test fun `merges into an existing base-name dir and skips a duplicate filename`() {
        val displayDir = File(guidesRoot(), "NES/Zelda II - The Adventure of Link").apply { mkdirs() }
        File(displayDir, "manual.pdf").writeText("from display-name dir")
        File(displayDir, "map.png").writeText("map")
        val baseDir = File(guidesRoot(), "NES/Zelda II - The Adventure of Link (USA)").apply { mkdirs() }
        File(baseDir, "manual.pdf").writeText("from kitchen upload")
        val roms = listOf(
            rom(1, "NES", "Zelda II - The Adventure of Link (USA).nes", "Zelda II - The Adventure of Link"),
        )

        migration(roms).migrateIfNeeded()

        assertFalse("the display-name dir must be gone after a merge", displayDir.exists())
        assertEquals("from kitchen upload", File(baseDir, "manual.pdf").readText())
        assertTrue(File(baseDir, "map.png").exists())
    }

    @Test fun `rewrites position keys embedding the old dir name for a plain rename`() {
        val dir = File(guidesRoot(), "NES/Zelda II - The Adventure of Link").apply { mkdirs() }
        File(dir, "manual.pdf").writeText("x")
        val pf = positionsFile()
        IniWriter.write(
            pf,
            mapOf(
                "positions" to mapOf("NES/Zelda II - The Adventure of Link/manual.pdf" to "3"),
                "scroll_y" to mapOf("NES/Zelda II - The Adventure of Link/manual.pdf" to "120"),
            ),
        )
        val roms = listOf(
            rom(1, "NES", "Zelda II - The Adventure of Link (USA).nes", "Zelda II - The Adventure of Link"),
        )

        migration(roms).migrateIfNeeded()

        val ini = IniParser.parse(pf)
        assertEquals("3", ini.get("positions", "NES/Zelda II - The Adventure of Link (USA)/manual.pdf"))
        assertEquals("120", ini.get("scroll_y", "NES/Zelda II - The Adventure of Link (USA)/manual.pdf"))
        assertNull(ini.get("positions", "NES/Zelda II - The Adventure of Link/manual.pdf"))
    }

    @Test fun `drops the position key for a filename skipped as a duplicate during merge`() {
        val displayDir = File(guidesRoot(), "NES/Zelda II - The Adventure of Link").apply { mkdirs() }
        File(displayDir, "manual.pdf").writeText("from display-name dir")
        val baseDir = File(guidesRoot(), "NES/Zelda II - The Adventure of Link (USA)").apply { mkdirs() }
        File(baseDir, "manual.pdf").writeText("from kitchen upload")
        val pf = positionsFile()
        IniWriter.write(
            pf,
            mapOf(
                "positions" to mapOf(
                    "NES/Zelda II - The Adventure of Link/manual.pdf" to "9",
                    "NES/Zelda II - The Adventure of Link (USA)/manual.pdf" to "2",
                ),
            ),
        )
        val roms = listOf(
            rom(1, "NES", "Zelda II - The Adventure of Link (USA).nes", "Zelda II - The Adventure of Link"),
        )

        migration(roms).migrateIfNeeded()

        val ini = IniParser.parse(pf)
        assertEquals("2", ini.get("positions", "NES/Zelda II - The Adventure of Link (USA)/manual.pdf"))
        assertNull(ini.get("positions", "NES/Zelda II - The Adventure of Link/manual.pdf"))
    }

    @Test fun `stamps the version and never re-runs on a later call`() {
        val dir = File(guidesRoot(), "NES/Zelda II - The Adventure of Link").apply { mkdirs() }
        File(dir, "manual.pdf").writeText("x")
        val roms = listOf(
            rom(1, "NES", "Zelda II - The Adventure of Link (USA).nes", "Zelda II - The Adventure of Link"),
        )
        val migration = migration(roms)
        migration.migrateIfNeeded()
        assertTrue(File(guidesRoot(), ".guides_key_version").exists())

        // A dir recreated at the old display-name key after the stamp is written (a restored
        // backup, e.g.) must not be touched by a later call, since the migration never re-runs.
        val staleDir = File(guidesRoot(), "NES/Zelda II - The Adventure of Link").apply { mkdirs() }
        File(staleDir, "notes.txt").writeText("x")
        migration.migrateIfNeeded()

        assertTrue(staleDir.exists())
    }

    @Test fun `a failed rename does not stamp, so the pass retries and succeeds on a later call`() {
        val tagDir = File(guidesRoot(), "NES").apply { mkdirs() }
        val dir = File(tagDir, "Zelda II - The Adventure of Link").apply { mkdirs() }
        File(dir, "manual.pdf").writeText("x")
        val roms = listOf(
            rom(1, "NES", "Zelda II - The Adventure of Link (USA).nes", "Zelda II - The Adventure of Link"),
        )
        val migration = migration(roms)

        // Renaming a dir needs write access to its parent, not the dir itself, so blocking the
        // tag dir is what makes sourceDir.renameTo(targetDir) fail.
        tagDir.setWritable(false)
        assumeTrue("this user can create entries in a non-writable directory", !tagDir.canWrite())
        try {
            migration.migrateIfNeeded()
        } finally {
            tagDir.setWritable(true)
        }

        assertTrue("a failed rename must leave the source dir in place", dir.exists())
        assertFalse("a partial failure must not stamp", File(guidesRoot(), ".guides_key_version").exists())

        migration.migrateIfNeeded()

        assertFalse(dir.exists())
        assertTrue(File(tagDir, "Zelda II - The Adventure of Link (USA)").exists())
        assertTrue(File(guidesRoot(), ".guides_key_version").exists())
    }

    @Test fun `a file that fails to move during a merge keeps its position key and blocks the stamp`() {
        val tagDir = File(guidesRoot(), "NES").apply { mkdirs() }
        val displayDir = File(tagDir, "Zelda II - The Adventure of Link").apply { mkdirs() }
        File(displayDir, "manual.pdf").writeText("from display-name dir")
        val baseDir = File(tagDir, "Zelda II - The Adventure of Link (USA)").apply { mkdirs() }
        val pf = positionsFile()
        IniWriter.write(
            pf,
            mapOf("positions" to mapOf("NES/Zelda II - The Adventure of Link/manual.pdf" to "5")),
        )
        val roms = listOf(
            rom(1, "NES", "Zelda II - The Adventure of Link (USA).nes", "Zelda II - The Adventure of Link"),
        )

        // Moving a file into the target dir needs write access to the target, not the source.
        baseDir.setWritable(false)
        assumeTrue("this user can create entries in a non-writable directory", !baseDir.canWrite())
        try {
            migration(roms).migrateIfNeeded()
        } finally {
            baseDir.setWritable(true)
        }

        assertTrue("the file that failed to move must remain at the old location", File(displayDir, "manual.pdf").exists())
        val ini = IniParser.parse(pf)
        assertEquals("still at the old location, its key must be untouched", "5", ini.get("positions", "NES/Zelda II - The Adventure of Link/manual.pdf"))
        assertNull(ini.get("positions", "NES/Zelda II - The Adventure of Link (USA)/manual.pdf"))
        assertFalse("a partial merge failure must not stamp", File(guidesRoot(), ".guides_key_version").exists())
    }

    @Test fun `a merge that cannot remove the emptied source dir still stamps, since no guide content was stranded`() {
        val tagDir = File(guidesRoot(), "NES").apply { mkdirs() }
        val displayDir = File(tagDir, "Zelda II - The Adventure of Link").apply { mkdirs() }
        File(displayDir, "manual.pdf").writeText("x")
        // A stray subdirectory is not a guide file; mergeInto never touches it, so it blocks
        // sourceDir.delete() even after every real file was successfully moved out.
        File(File(displayDir, "stray").apply { mkdirs() }, "leftover.txt").writeText("x")
        val baseDir = File(tagDir, "Zelda II - The Adventure of Link (USA)").apply { mkdirs() }
        val roms = listOf(
            rom(1, "NES", "Zelda II - The Adventure of Link (USA).nes", "Zelda II - The Adventure of Link"),
        )

        migration(roms).migrateIfNeeded()

        assertTrue(File(baseDir, "manual.pdf").exists())
        assertTrue("the source dir survives because a stray subdir blocks delete", displayDir.exists())
        assertTrue(File(guidesRoot(), ".guides_key_version").exists())
    }

    @Test fun `resolves the guides directory fresh on every call, not once at construction`() {
        val staleRoot = tmp.newFolder("Guides-stale")
        val freshRoot = tmp.newFolder("Guides-fresh")
        File(File(staleRoot, "NES"), "Zelda II - The Adventure of Link").mkdirs()
        File(File(freshRoot, "NES"), "Zelda II - The Adventure of Link").mkdirs()
        val roms = listOf(
            rom(1, "NES", "Zelda II - The Adventure of Link (USA).nes", "Zelda II - The Adventure of Link"),
        )

        var current = staleRoot
        val migration = GuidesKeyMigration(
            guidesDir = { current },
            positionsFile = { positionsFile() },
            roms = { roms },
        )
        current = freshRoot
        migration.migrateIfNeeded()

        assertTrue(File(freshRoot, "NES/Zelda II - The Adventure of Link (USA)").exists())
        assertTrue("the stale root must be untouched", File(staleRoot, "NES/Zelda II - The Adventure of Link").exists())
    }
}
