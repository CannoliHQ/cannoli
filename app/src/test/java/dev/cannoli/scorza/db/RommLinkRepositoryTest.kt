package dev.cannoli.scorza.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.settings.SettingsRepository
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
class RommLinkRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var romDir: File
    private lateinit var repo: RommLinkRepository

    @Before fun setUp() {
        root = tmp.newFolder("cannoli")
        romDir = File(root, "Roms").apply { mkdirs() }
        File(root, "Config").mkdirs()
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = root.absolutePath
        val paths = CannoliPathsProvider(settings)
        val db = CannoliDatabase(paths)
        repo = RommLinkRepository(db) { romDir }
    }

    @Test fun `present ids only include links whose file exists`() {
        File(romDir, "SNES").mkdirs()
        File(romDir, "SNES/Mario.sfc").writeText("x")
        repo.upsertLink(rommId = 10, relativePath = "SNES/Mario.sfc", source = "download")
        repo.upsertLink(rommId = 11, relativePath = "SNES/Gone.sfc", source = "manual")

        val present = repo.presentRommIds()

        assertTrue(present.contains(10))
        assertFalse(present.contains(11))
    }

    @Test fun `upsert replaces an existing link and removeLink deletes it`() {
        File(romDir, "GBA").mkdirs()
        File(romDir, "GBA/Zelda.gba").writeText("x")
        repo.upsertLink(rommId = 20, relativePath = "GBA/old.gba", source = "download")
        repo.upsertLink(rommId = 20, relativePath = "GBA/Zelda.gba", source = "manual")
        assertEquals(setOf(20), repo.presentRommIds())

        repo.removeLink(20)
        assertEquals(emptySet<Int>(), repo.presentRommIds())
    }
}
