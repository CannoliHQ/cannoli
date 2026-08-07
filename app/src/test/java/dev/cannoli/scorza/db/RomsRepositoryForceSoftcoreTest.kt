package dev.cannoli.scorza.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.ArtworkLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RomsRepositoryForceSoftcoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: CannoliDatabase
    private lateinit var repo: RomsRepository

    @Before fun setUp() {
        val root = tmp.newFolder("cannoli")
        File(root, "Config").mkdirs()
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = root.absolutePath
        val paths = CannoliPathsProvider(settings)
        db = CannoliDatabase(paths)
        repo = RomsRepository(paths, db, ArtworkLookup(paths))
    }

    private fun seedRom(): Long {
        db.execute("INSERT INTO platforms (tag, display_name) VALUES ('NES', 'Nintendo')")
        db.execute(
            """
            INSERT INTO roms (path, platform_tag, display_name, sort_key, tags, ra_game_id, last_played_at, ra_cached_game_id)
            VALUES ('NES/a.nes', 'NES', 'A', 'a', 'USA', 111, 222, 333)
            """.trimIndent(),
        )
        return repo.romIdForRelativePath("NES/a.nes")!!
    }

    @Test fun `a freshly scanned rom does not force softcore`() {
        assertFalse(repo.gameById(seedRom())!!.forceSoftcore)
    }

    @Test fun `the flag survives a round trip`() {
        val id = seedRom()
        repo.setForceSoftcore(id, true)
        assertTrue(repo.gameById(id)!!.forceSoftcore)
        repo.setForceSoftcore(id, false)
        assertFalse(repo.gameById(id)!!.forceSoftcore)
    }

    /** The new column widens BASE_SELECT, so every other mapped column has to keep its index. */
    @Test fun `the surrounding columns still map to the right fields`() {
        val id = seedRom()
        repo.setForceSoftcore(id, true)
        val rom = repo.gameById(id)!!
        assertEquals("A", rom.displayName)
        assertEquals("USA", rom.tags)
        assertEquals(111, rom.raGameId)
        assertEquals(222L, rom.lastPlayedAt)
        assertEquals(333, rom.raCachedGameId)
    }
}
