package dev.cannoli.scorza.romm.sync

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
class SaveSyncServicePreLaunchTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var client: RommClient
    private lateinit var service: SaveSyncService
    private lateinit var sd: File
    private lateinit var store: SaveSyncStore

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
        every { connStore.serverVersion } returns "5.0.0"
        client = mockk(relaxed = true)
        val registrar = mockk<DeviceRegistrar>(); every { registrar.deviceId() } returns "dev-1"
        val resolver = LocalSaveResolver(paths.root)
        service = SaveSyncService(client, connStore, settings, registrar, store, resolver, links, paths, SaveBackupManager(paths.root, resolver), SyncHistoryStore(db), PendingConflictStore(db), RestorePromotionStore(db), SaveSyncStatusHolder(), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
    }

    private fun writeSave() {
        File(sd, "Saves/SNES").mkdirs()
        File(sd, "Saves/SNES/Mario.srm").writeBytes("LOCAL".toByteArray())
    }

    @Test fun download_op_writes_file_and_proceeds() = runTest {
        writeSave()
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(SyncOperationDto(action = "download", romId = 42, saveId = 100, fileName = "Mario.srm", slot = "autosave", serverUpdatedAt = "2026-06-26T01:00:00Z")),
            totalDownload = 1,
        )
        val destSlot = slot<File>()
        every { client.downloadSaveContent(100, "dev-1", capture(destSlot)) } answers { destSlot.captured.writeBytes("SERVER".toByteArray()) }
        every { client.confirmSaveDownloaded(100, "dev-1") } returns RommSaveDto(id = 100, slot = "autosave")
        val outcome = service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x")
        assertTrue(outcome is PreLaunchOutcome.Proceed)
        assertTrue(File(sd, "Saves/SNES/Mario/Mario.srm").readText() == "SERVER")
    }

    @Test fun `an upload with no anchor never replaces a different server save`() = runTest {
        writeSave()
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(
                SyncOperationDto(
                    action = "upload", romId = 42, saveId = 100, fileName = "Mario.srm",
                    slot = "autosave", serverUpdatedAt = "2026-06-26T01:00:00Z",
                    serverContentHash = "428480b3",
                )
            ),
            totalDownload = 0,
        )

        val outcome = service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x")

        assertTrue("expected a conflict, got $outcome", outcome is PreLaunchOutcome.Conflict)
        verify(exactly = 0) { client.uploadSave(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `an upload with no anchor proceeds when the server has no save`() = runTest {
        writeSave()
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(
                SyncOperationDto(
                    action = "upload", romId = 42, saveId = null, fileName = "Mario.srm",
                    slot = "autosave", serverUpdatedAt = "2026-06-26T01:00:00Z",
                    serverContentHash = null,
                )
            ),
            totalDownload = 0,
        )

        val outcome = service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x")

        assertTrue(outcome is PreLaunchOutcome.Proceed)
        verify { client.uploadSave(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a 409 on the launch path becomes a conflict instead of a log line`() = runTest {
        writeSave()
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(
                SyncOperationDto(
                    action = "upload", romId = 42, saveId = null, fileName = "Mario.srm",
                    slot = "autosave", serverUpdatedAt = "2026-06-26T01:00:00Z",
                )
            ),
            totalDownload = 0,
        )
        every { client.uploadSave(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            dev.cannoli.scorza.romm.RommException(409, "HTTP 409 Conflict")

        val outcome = service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x")

        assertTrue("expected a conflict, got $outcome", outcome is PreLaunchOutcome.Conflict)
    }

    @Test fun `a missing anchor is rebuilt from the server's own record`() = runTest {
        writeSave()
        every { client.getSaves(42, "dev-1", any()) } returns listOf(
            RommSaveDto(
                id = 100, romId = 42, slot = "autosave", contentHash = "server-hash",
                updatedAt = "2026-09-01T10:00:00+00:00",
                deviceSyncs = listOf(
                    DeviceSyncDto(deviceId = "dev-1", lastSyncedAt = "2026-09-02T10:00:00+00:00", isCurrent = true)
                ),
            )
        )
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(sessionId = 1, operations = emptyList())

        service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x")

        val anchor = store.get("SNES/Mario.sfc", "autosave")
        assertEquals("server-hash", anchor?.lastUploadedHash)
    }

    @Test fun `an anchor is not invented when the server has moved on`() = runTest {
        writeSave()
        every { client.getSaves(42, "dev-1", any()) } returns listOf(
            RommSaveDto(
                id = 100, romId = 42, slot = "autosave", contentHash = "server-hash",
                updatedAt = "2026-09-03T10:00:00+00:00",
                deviceSyncs = listOf(
                    DeviceSyncDto(deviceId = "dev-1", lastSyncedAt = "2026-09-02T10:00:00+00:00", isCurrent = true)
                ),
            )
        )
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(sessionId = 1, operations = emptyList())

        service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x")

        assertEquals(null, store.get("SNES/Mario.sfc", "autosave"))
    }

    @Test fun `negotiate reports the save on disk, not the last uploaded hash`() = runTest {
        writeSave()
        store.upsert(
            SaveSyncRow(
                gameKey = "SNES/Mario.sfc",
                slot = "autosave",
                rommRomId = 42,
                rommSaveId = 100,
                lastSyncedAt = "2026-09-01T10:00:00Z",
                lastUploadedHash = "0000000000000000000000000000dead",
                localContentHash = "0000000000000000000000000000dead",
                serverUpdatedAt = "2026-09-01T10:00:00Z",
                updatedAt = System.currentTimeMillis(),
            )
        )
        val payload = slot<SyncNegotiatePayload>()
        every { client.negotiateSync(capture(payload)) } returns SyncNegotiateResponse(sessionId = 1, totalNoOp = 1)

        service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x")

        assertEquals(
            SaveHasher.md5Hex("LOCAL".toByteArray()),
            payload.captured.saves.single().contentHash,
        )
    }

    @Test fun conflict_op_returns_conflict() = runTest {
        writeSave()
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(SyncOperationDto(action = "conflict", romId = 42, saveId = 100, fileName = "Mario.srm", slot = "autosave", serverUpdatedAt = "2026-06-26T01:00:00Z")),
            totalConflict = 1,
        )
        val outcome = service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x")
        assertTrue(outcome is PreLaunchOutcome.Conflict)
    }

    @Test fun download_failure_blocks_known_stale() = runTest {
        writeSave()
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(SyncOperationDto(action = "download", romId = 42, saveId = 100, fileName = "Mario.srm", slot = "autosave")),
            totalDownload = 1,
        )
        every { client.downloadSaveContent(any(), any(), any()) } throws java.io.IOException("boom")
        val outcome = service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x")
        assertTrue(outcome is PreLaunchOutcome.KnownStaleBlock)
    }

    @Test fun negotiate_failure_proceeds_offline_first() = runTest {
        writeSave()
        every { client.negotiateSync(any()) } throws java.io.IOException("no network")
        val outcome = service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x")
        assertTrue(outcome is PreLaunchOutcome.Proceed)
    }

    @Test fun no_op_proceeds() = runTest {
        writeSave()
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(sessionId = 1, totalNoOp = 1)
        assertTrue(service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x") is PreLaunchOutcome.Proceed)
    }

    @Test fun download_op_null_saveId_blocks_known_stale() = runTest {
        writeSave()
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(SyncOperationDto(action = "download", romId = 42, saveId = null, fileName = "Mario.srm", slot = "autosave")),
            totalDownload = 1,
        )
        val outcome = service.syncBeforeLaunch("SNES", "Mario", "SNES/Mario.sfc", "snes9x")
        assertTrue(outcome is PreLaunchOutcome.KnownStaleBlock)
    }
}
