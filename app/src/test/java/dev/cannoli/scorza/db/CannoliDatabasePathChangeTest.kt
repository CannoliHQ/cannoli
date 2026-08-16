package dev.cannoli.scorza.db

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.di.CannoliPathsProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CannoliDatabasePathChangeTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var defaultRoot: File
    private lateinit var sdRoot: File
    private lateinit var current: File
    private lateinit var db: CannoliDatabase

    @Before fun setUp() {
        defaultRoot = tmp.newFolder("internal")
        sdRoot = tmp.newFolder("sdcard")
        current = defaultRoot
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } answers { current }
        every { paths.romDir } answers { File(current, "Roms") }
        db = CannoliDatabase(paths)
    }

    @After fun tearDown() = db.close()

    private fun insertPlatform(tag: String) =
        db.execute("INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)", tag, tag)

    private fun tags() = db.queryAll("SELECT tag FROM platforms ORDER BY tag") { it.getText(0) }

    @Test fun `rows written under the first root are not visible after the root changes`() {
        insertPlatform("NES")
        assertEquals(listOf("NES"), tags())
        assertTrue(CannoliPaths(defaultRoot).database.exists())

        current = sdRoot
        assertEquals(emptyList<String>(), tags())
        assertTrue(CannoliPaths(sdRoot).database.exists())
    }

    @Test fun `each root keeps its own rows across switches`() {
        insertPlatform("NES")
        current = sdRoot
        insertPlatform("SNES")
        assertEquals(listOf("SNES"), tags())

        current = defaultRoot
        assertEquals(listOf("NES"), tags())
    }

    @Test fun `a stable root reads back what it wrote`() {
        insertPlatform("NES")
        insertPlatform("SNES")
        assertEquals(listOf("NES", "SNES"), tags())
    }

    @Test fun `close without an open connection leaves the file untouched`() {
        db.close()
        assertFalse(CannoliPaths(defaultRoot).database.exists())
    }

    @Test fun `access after close reopens the database`() {
        insertPlatform("NES")
        db.close()
        assertEquals(listOf("NES"), tags())
    }
}
