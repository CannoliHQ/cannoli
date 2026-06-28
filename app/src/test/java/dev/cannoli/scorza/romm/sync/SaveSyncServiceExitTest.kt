package dev.cannoli.scorza.romm.sync

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.romm.RommException
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class SaveSyncServiceExitTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var client: RommClient
    private lateinit var service: SaveSyncService
    private lateinit var store: SaveSyncStore
    private lateinit var sd: File

    @Before fun setup() {
        sd = tmp.newFolder("SD")
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.sdCardRoot = sd.absolutePath
        settings.rommSaveSyncEnabled = true
        settings.rommDeviceId = "dev-1"
        val paths = CannoliPathsProvider(settings)
        val db = CannoliDatabase(paths)
        store = SaveSyncStore(db)
        val links = RommLinkRepository(db) { File(sd, "Roms") }
        links.upsertLink(42, "SNES/Mario.sfc", "download")
        val connStore = mockk<RommConnectionStore>(relaxed = true)
        every { connStore.isConfigured } returns true
        every { connStore.serverVersion } returns "4.9.0"
        client = mockk(relaxed = true)
        val registrar = mockk<DeviceRegistrar>(); every { registrar.deviceId() } returns "dev-1"
        val resolver = LocalSaveResolver(paths.root)
        service = SaveSyncService(client, connStore, settings, registrar, store, resolver, links, paths, SaveBackupManager(paths.root, resolver))
    }

    private fun writeSave(content: String = "LOCAL") {
        File(sd, "Saves/SNES").mkdirs()
        File(sd, "Saves/SNES/Mario.srm").writeBytes(content.toByteArray())
    }

    @Test fun changed_local_uploads() = runTest {
        writeSave()
        every { client.uploadSave(any(), any(), any(), any(), any(), any()) } returns RommSaveDto(id = 100, slot = "autosave", contentHash = "h", updatedAt = "t")
        val result = service.syncAfterExit("SNES", "Mario", "SNES/Mario.sfc", "snes9x")
        assertTrue(result)
        verify { client.uploadSave(42, "snes9x", "autosave", "dev-1", false, any()) }
        assertEquals(100, store.get("SNES/Mario.sfc", "autosave")?.rommSaveId)
    }

    @Test fun unchanged_local_skips_upload() = runTest {
        writeSave()
        val hash = SaveHasher.hashFile(File(sd, "Saves/SNES/Mario.srm"))
        store.upsert(
            SaveSyncRow(
                gameKey = "SNES/Mario.sfc",
                slot = DEFAULT_SLOT,
                rommRomId = 42,
                rommSaveId = 77,
                lastSyncedAt = null,
                lastUploadedHash = hash,
                localContentHash = hash,
                serverUpdatedAt = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
        val result = service.syncAfterExit("SNES", "Mario", "SNES/Mario.sfc", "snes9x")
        assertFalse(result)
        verify(exactly = 0) { client.uploadSave(any(), any(), any(), any(), any(), any()) }
        assertEquals(77, store.get("SNES/Mario.sfc", DEFAULT_SLOT)?.rommSaveId)
    }

    @Test fun conflict_409_defers_no_crash() = runTest {
        writeSave()
        every { client.uploadSave(any(), any(), any(), any(), any(), any()) } throws RommException(409, "newer")
        val result = service.syncAfterExit("SNES", "Mario", "SNES/Mario.sfc", "snes9x")
        assertFalse(result)
        assertNull(store.get("SNES/Mario.sfc", DEFAULT_SLOT))
    }
}
