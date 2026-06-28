package dev.cannoli.scorza.romm.sync

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveSyncStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: SaveSyncStore

    @Before fun setup() {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.sdCardRoot = tmp.newFolder("SD").absolutePath
        val paths = CannoliPathsProvider(settings)
        store = SaveSyncStore(CannoliDatabase(paths))
    }

    @Test fun upsert_then_get_roundtrips() {
        val row = SaveSyncRow("SNES/Mario.sfc", "autosave", 42, 100, "2026-06-26T00:00:00Z", "h1", "h1", "2026-06-26T00:00:00Z", 123L)
        store.upsert(row)
        assertEquals(row, store.get("SNES/Mario.sfc", "autosave"))
    }

    @Test fun get_missing_returns_null() {
        assertNull(store.get("SNES/None.sfc", "autosave"))
    }

    @Test fun listSlots_returns_all_slots_for_a_game() {
        store.upsert(SaveSyncRow("SNES/Mario.sfc", "autosave", 42, 1, null, null, null, null, 1L))
        store.upsert(SaveSyncRow("SNES/Mario.sfc", "before-boss", 42, 2, null, null, null, null, 2L))
        store.upsert(SaveSyncRow("SNES/Other.sfc", "autosave", 43, 3, null, null, null, null, 3L))
        assertEquals(setOf("autosave", "before-boss"), store.listSlots("SNES/Mario.sfc").map { it.slot }.toSet())
    }

    @Test fun active_slot_defaults_to_autosave_then_persists() {
        assertEquals("autosave", store.activeSlot("SNES/Mario.sfc"))
        store.setActiveSlot("SNES/Mario.sfc", "before-boss")
        assertEquals("before-boss", store.activeSlot("SNES/Mario.sfc"))
    }

    @Test fun delete_removes_only_that_slot() {
        store.upsert(SaveSyncRow("SNES/Mario.sfc", "autosave", 42, 1, null, null, null, null, 1L))
        store.upsert(SaveSyncRow("SNES/Mario.sfc", "before-boss", 42, 2, null, null, null, null, 2L))
        store.delete("SNES/Mario.sfc", "before-boss")
        assertEquals(listOf("autosave"), store.listSlots("SNES/Mario.sfc").map { it.slot })
    }
}
