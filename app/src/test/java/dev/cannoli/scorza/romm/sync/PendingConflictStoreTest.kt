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
class PendingConflictStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: CannoliDatabase

    @Before fun setup() {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.sdCardRoot = tmp.newFolder("SD").absolutePath
        val paths = CannoliPathsProvider(settings)
        db = CannoliDatabase(paths)
    }

    @Test fun `upsert get delete count`() {
        val store = PendingConflictStore(db)
        store.upsert(PendingConflict("zelda.sfc", 10, "Zelda", 5, "h1", "2024-01-01T00:00:00", 100L, null))
        assertEquals(1, store.count())
        assertEquals("Zelda", store.get("zelda.sfc")!!.displayName)
        store.delete("zelda.sfc")
        assertEquals(0, store.count())
    }

    @Test fun `markDismissed records the dismissed hash`() {
        val store = PendingConflictStore(db)
        store.upsert(PendingConflict("g", 1, "G", 2, "h", "t", 1L, null))
        store.markDismissed("g", "h")
        assertEquals("h", store.get("g")!!.dismissedHash)
    }
}
