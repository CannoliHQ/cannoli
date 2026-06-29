package dev.cannoli.scorza.romm.sync

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncHistoryStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: CannoliDatabase

    @Before fun setup() {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.sdCardRoot = tmp.newFolder("SD").absolutePath
        val paths = CannoliPathsProvider(settings)
        db = CannoliDatabase(paths)
    }

    @Test fun `add keeps newest cap and returns newest first`() {
        val store = SyncHistoryStore(db, cap = 3)
        for (i in 1..5) store.add(SyncHistoryEntry("g$i", "Game $i", SyncDirection.DOWNLOAD, null, i.toLong()))
        val recent = store.recent()
        assertEquals(listOf("Game 5", "Game 4", "Game 3"), recent.map { it.displayName })
    }

    @Test fun `conflict and error directions round-trip with detail`() {
        val store = SyncHistoryStore(db, cap = 100)
        store.add(SyncHistoryEntry("g", "Zelda", SyncDirection.CONFLICT, "both changed", 10L))
        val e = store.recent().single()
        assertEquals(SyncDirection.CONFLICT, e.direction)
        assertEquals("both changed", e.detail)
    }
}
