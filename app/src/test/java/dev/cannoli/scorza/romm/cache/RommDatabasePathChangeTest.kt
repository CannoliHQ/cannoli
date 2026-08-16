package dev.cannoli.scorza.romm.cache

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommDatabasePathChangeTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var defaultFile: File
    private lateinit var sdFile: File
    private lateinit var current: File
    private lateinit var db: RommDatabase

    @Before fun setUp() {
        defaultFile = File(tmp.newFolder("internal"), "romm.db")
        sdFile = File(tmp.newFolder("sdcard"), "romm.db")
        current = defaultFile
        db = RommDatabase { current }
    }

    @After fun tearDown() = db.close()

    @Test fun `data written under the first path is not visible after the provider switches`() {
        db.setSyncState("last_sync", "internal")
        assertEquals("internal", db.getSyncState("last_sync"))
        assertTrue(defaultFile.exists())

        current = sdFile
        assertNull(db.getSyncState("last_sync"))
        assertTrue(sdFile.exists())
    }

    @Test fun `each path keeps its own rows across switches`() {
        db.setSyncState("k", "internal")
        current = sdFile
        db.setSyncState("k", "sd")
        assertEquals("sd", db.getSyncState("k"))

        current = defaultFile
        assertEquals("internal", db.getSyncState("k"))
    }

    @Test fun `a stable path reads back what it wrote`() {
        db.setSyncState("a", "1")
        db.setSyncState("b", "2")
        assertEquals("1", db.getSyncState("a"))
        assertEquals("2", db.getSyncState("b"))
    }

    @Test fun `close without an open connection leaves the file untouched`() {
        db.close()
        assertFalse(defaultFile.exists())
    }

    @Test fun `access after close reopens the database`() {
        db.setSyncState("k", "internal")
        db.close()
        assertEquals("internal", db.getSyncState("k"))
    }
}
