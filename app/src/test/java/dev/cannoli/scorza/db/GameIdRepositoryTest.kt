package dev.cannoli.scorza.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.sigil.GameId
import dev.cannoli.scorza.sigil.GameIdStatus
import dev.cannoli.scorza.sigil.IdSource
import dev.cannoli.scorza.sigil.SaveUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameIdRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: CannoliDatabase
    private lateinit var paths: CannoliPathsProvider
    private lateinit var repo: GameIdRepository

    @Before fun setUp() {
        val root = tmp.newFolder("cannoli")
        File(root, "Config").mkdirs()
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = root.absolutePath
        paths = CannoliPathsProvider(settings)
        db = CannoliDatabase(paths)
        repo = GameIdRepository(paths, db)
    }

    private fun seed(tag: String, relative: String): Long {
        db.execute("INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)", tag, tag)
        db.execute(
            "INSERT INTO roms (path, platform_tag, display_name, sort_key) VALUES (?, ?, ?, ?)",
            relative, tag, relative, relative,
        )
        return db.queryOne("SELECT id FROM roms WHERE path = ?", relative) { it.getLong(0) }!!
    }

    private val anId = GameId("SLUS-20946", "BASLUS-20946", "SLUS_209.46", SaveUsage.FOLDER_PREFIX, IdSource.BINARY, false)

    @Test fun `pending returns unprobed rows on the asked-for tags only`() {
        val ps2 = seed("PS2", "PS2/Game.iso")
        seed("SNES", "SNES/Game.sfc")

        val pending = repo.pending(listOf("PS2", "PS"), 10)

        assertEquals(listOf(ps2), pending.map { it.romId })
        assertEquals(File(paths.romDir, "PS2/Game.iso"), pending.single().file)
    }

    @Test fun `a recorded row drops out of pending whether or not it found anything`() {
        val found = seed("PS2", "PS2/Found.iso")
        val missed = seed("PS2", "PS2/Missed.iso")

        repo.record(found, "1:2", anId)
        repo.record(missed, "3:4", null)

        assertTrue(repo.pending(listOf("PS2"), 10).isEmpty())
    }

    @Test fun `pending honours its limit`() {
        repeat(4) { seed("PS2", "PS2/Game$it.iso") }
        assertEquals(2, repo.pending(listOf("PS2"), 2).size)
    }

    @Test fun `pending on no tags asks the database nothing`() {
        seed("PS2", "PS2/Game.iso")
        assertTrue(repo.pending(emptyList(), 10).isEmpty())
    }

    /** The three states the overlay draws, and the reader has to tell them apart. */
    @Test fun `an unprobed row reads as pending`() {
        assertEquals(GameIdStatus.Pending, repo.read(seed("PS2", "PS2/Game.iso")))
    }

    @Test fun `a probed row with no id reads as not found`() {
        val id = seed("PS2", "PS2/Game.iso")
        repo.record(id, "1:2", null)
        assertEquals(GameIdStatus.NotFound, repo.read(id))
    }

    @Test fun `every field survives the round trip`() {
        val id = seed("PS2", "PS2/Game.iso")
        repo.record(id, "1:2", anId)
        assertEquals(GameIdStatus.Found(anId), repo.read(id))
    }

    @Test fun `the experimental flag survives both ways`() {
        val id = seed("PS2", "PS2/Game.iso")
        repo.record(id, "1:2", anId.copy(experimental = true))
        assertEquals(true, (repo.read(id) as GameIdStatus.Found).id.experimental)
        repo.record(id, "1:2", anId)
        assertEquals(false, (repo.read(id) as GameIdStatus.Found).id.experimental)
    }

    /** Re-probing overwrites rather than accumulating, so a corrected id replaces the wrong one. */
    @Test fun `recording again replaces what was there`() {
        val id = seed("PS2", "PS2/Game.iso")
        repo.record(id, "1:2", anId)
        repo.record(id, "5:6", null)
        assertEquals(GameIdStatus.NotFound, repo.read(id))
    }
}
