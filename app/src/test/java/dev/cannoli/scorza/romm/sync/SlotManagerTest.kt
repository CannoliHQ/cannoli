package dev.cannoli.scorza.romm.sync

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class SlotManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var client: RommClient
    private lateinit var service: SaveSyncService
    private lateinit var store: SaveSyncStore
    private lateinit var slotManager: SlotManager
    private lateinit var sd: File

    @Before fun setup() {
        sd = tmp.newFolder("SD")
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.sdCardRoot = sd.absolutePath
        settings.rommDeviceId = "dev-1"
        val paths = CannoliPathsProvider(settings)
        val db = CannoliDatabase(paths)
        store = SaveSyncStore(db)
        client = mockk(relaxed = true)
        val registrar = mockk<DeviceRegistrar>()
        every { registrar.deviceId() } returns "dev-1"
        val resolver = LocalSaveResolver(paths.root)
        service = mockk(relaxed = true)
        slotManager = SlotManager(client, store, resolver, registrar, paths, service)
        File(sd, "Saves/SNES").mkdirs()
        File(sd, "Saves/SNES/Mario.srm").writeBytes("LOCAL".toByteArray())
    }

    @Test fun create_snapshots_current_and_activates() = runTest {
        slotManager.create("SNES/Mario.sfc", "SNES", "Mario", 42, "snes9x", "before-boss")
        verify { service.uploadActive("SNES", "Mario", "SNES/Mario.sfc", "before-boss", 42, "snes9x", "dev-1", false) }
        assertEquals("before-boss", store.activeSlot("SNES/Mario.sfc"))
    }

    @Test fun delete_autosave_is_rejected() = runTest {
        try {
            slotManager.delete("SNES/Mario.sfc", 42, "autosave")
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun delete_active_named_slot_falls_back_to_autosave() = runTest {
        store.upsert(SaveSyncRow("SNES/Mario.sfc", "before-boss", 42, 7, "t", "h", "h", "t", 1L))
        store.setActiveSlot("SNES/Mario.sfc", "before-boss")
        every { client.getSaves(42, "dev-1") } returns emptyList()
        slotManager.delete("SNES/Mario.sfc", 42, "before-boss")
        assertEquals(DEFAULT_SLOT, store.activeSlot("SNES/Mario.sfc"))
        assertNull(store.get("SNES/Mario.sfc", "before-boss"))
    }

    @Test fun rename_migrates_and_deletes_old() = runTest {
        store.upsert(SaveSyncRow("SNES/Mario.sfc", "old", 42, 9, "t", "h", "h", "t", 1L))
        store.setActiveSlot("SNES/Mario.sfc", "old")
        slotManager.rename("SNES/Mario.sfc", "SNES", "Mario", 42, "snes9x", "old", "new")
        verify { service.uploadActive("SNES", "Mario", "SNES/Mario.sfc", "new", 42, "snes9x", "dev-1", false) }
        verify { client.deleteSaves(listOf(9)) }
        assertEquals("new", store.activeSlot("SNES/Mario.sfc"))
    }
}
