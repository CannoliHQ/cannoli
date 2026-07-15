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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
class SaveSyncSweepTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var client: RommClient
    private lateinit var service: SaveSyncService
    private lateinit var historyStore: SyncHistoryStore
    private lateinit var pendingStore: PendingConflictStore
    private lateinit var store: SaveSyncStore
    private lateinit var promotionStore: RestorePromotionStore
    private lateinit var backupManager: SaveBackupManager
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
        historyStore = SyncHistoryStore(db)
        pendingStore = PendingConflictStore(db)
        promotionStore = RestorePromotionStore(db)
        val links = RommLinkRepository(db) { File(sd, "Roms") }
        links.upsertLink(42, "SNES/Zelda.sfc", "download")
        val connStore = mockk<RommConnectionStore>(relaxed = true)
        every { connStore.isConfigured } returns true
        every { connStore.serverVersion } returns "4.9.0"
        client = mockk(relaxed = true)
        val registrar = mockk<DeviceRegistrar>(); every { registrar.deviceId() } returns "dev-1"
        val resolver = LocalSaveResolver(paths.root)
        backupManager = SaveBackupManager(paths.root, resolver)
        val roms = mockk<dev.cannoli.scorza.db.RomsRepository>(relaxed = true)
        every { roms.allRelativePaths() } returns listOf("SNES/Zelda.sfc")
        service = SaveSyncService(
            client, connStore, settings, registrar, store, resolver, links, paths,
            backupManager, historyStore, pendingStore, promotionStore, SaveSyncStatusHolder(),
            mockk(relaxed = true), roms,
        )
    }

    private fun writeSave(content: String = "LOCAL") {
        File(sd, "Saves/SNES").mkdirs()
        File(sd, "Saves/SNES/Zelda.srm").writeBytes(content.toByteArray())
    }

    // Seed the anchor row so the auto-resolver has both anchors to compare.
    // serverAnchor = lastUploadedHash = "server-hash-unchanged"
    // localAnchor = localContentHash = "old-local-hash"
    private fun seedAnchor(lastUploadedHash: String, localContentHash: String) {
        store.upsert(
            SaveSyncRow(
                gameKey = "SNES/Zelda.sfc",
                slot = DEFAULT_SLOT,
                rommRomId = 42,
                rommSaveId = 99,
                lastSyncedAt = null,
                lastUploadedHash = lastUploadedHash,
                localContentHash = localContentHash,
                serverUpdatedAt = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    @Test fun `sweep auto-resolves one-sided conflict and does not escalate`() = runBlocking {
        writeSave("NEW-LOCAL")
        val localHash = SaveHasher.hashFile(File(sd, "Saves/SNES/Zelda.srm"))
        // server unchanged: serverContentHash == lastUploadedHash; local changed: localHash != localContentHash
        val serverHash = "server-hash-unchanged"
        seedAnchor(lastUploadedHash = serverHash, localContentHash = "old-local-hash")

        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(
                SyncOperationDto(
                    action = "conflict",
                    romId = 42,
                    saveId = 100,
                    fileName = "Zelda.srm",
                    slot = "autosave",
                    serverUpdatedAt = "2026-06-26T01:00:00Z",
                    serverContentHash = serverHash,
                )
            ),
            totalConflict = 1,
        )
        every { client.uploadSave(any(), any(), any(), any(), any(), any()) } returns RommSaveDto(id = 101, slot = "autosave", contentHash = localHash, updatedAt = "2026-06-26T02:00:00Z")

        val summary = service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(0, pendingStore.count())
        assertEquals(SyncDirection.UPLOAD, historyStore.recent().first().direction)
        assertEquals(1, summary.uploaded)
    }

    @Test fun `sweep escalates a true conflict`() = runBlocking {
        writeSave("NEW-LOCAL")
        // both local and server changed from their anchors -> ESCALATE
        seedAnchor(lastUploadedHash = "old-server-hash", localContentHash = "old-local-hash")

        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(
                SyncOperationDto(
                    action = "conflict",
                    romId = 42,
                    saveId = 100,
                    fileName = "Zelda.srm",
                    slot = "autosave",
                    serverUpdatedAt = "2026-06-26T01:00:00Z",
                    serverContentHash = "new-server-hash",
                )
            ),
            totalConflict = 1,
        )
        service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(1, pendingStore.count())
        assertEquals(SyncDirection.CONFLICT, historyStore.recent().first().direction)
    }

    @Test fun `a persistent conflict is logged to history only once`() = runBlocking {
        writeSave("NEW-LOCAL")
        seedAnchor(lastUploadedHash = "old-server-hash", localContentHash = "old-local-hash")
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(
                SyncOperationDto(
                    action = "conflict",
                    romId = 42,
                    saveId = 100,
                    fileName = "Zelda.srm",
                    slot = "autosave",
                    serverUpdatedAt = "2026-06-26T01:00:00Z",
                    serverContentHash = "new-server-hash",
                )
            ),
            totalConflict = 1,
        )
        repeat(3) { service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true) }

        assertEquals(1, pendingStore.count())
        assertEquals(1, historyStore.recent().count { it.direction == SyncDirection.CONFLICT })
    }

    @Test fun `sweep uploads a changed local save`() = runBlocking {
        writeSave("CHANGED")
        seedAnchor(lastUploadedHash = "old-server-hash", localContentHash = "old-local-hash")
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(sessionId = 1, operations = emptyList())
        every { client.uploadSave(any(), any(), any(), any(), any(), any()) } returns RommSaveDto(id = 55, slot = "autosave", contentHash = "h", updatedAt = "t")

        val summary = service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(1, summary.uploaded)
        assertEquals(SyncDirection.UPLOAD, historyStore.recent().first().direction)
    }

    @Test fun `sweep leaves an unchanged local save alone`() = runBlocking {
        writeSave("LOCAL")
        val hash = SaveHasher.hashFile(File(sd, "Saves/SNES/Zelda.srm"))
        seedAnchor(lastUploadedHash = hash, localContentHash = hash)
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(sessionId = 1, operations = emptyList())

        val summary = service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(0, summary.uploaded)
    }

    @Test fun `sweep pulls server save when local missing and no anchor`() = runBlocking {
        // no local save, no anchor: the server copy should still be pulled down.
        every { client.getSaves(42, "dev-1") } returns listOf(
            RommSaveDto(id = 77, romId = 42, slot = "autosave", contentHash = "srv", updatedAt = "2026-06-26T01:00:00Z")
        )
        every { client.downloadSaveContent(77, "dev-1", any()) } answers { thirdArg<File>().writeBytes("RESTORED".toByteArray()) }

        val summary = service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(1, summary.downloaded)
        assertEquals(SyncDirection.DOWNLOAD, historyStore.recent().first().direction)
        assertEquals(true, store.get("SNES/Zelda.sfc", DEFAULT_SLOT) != null)
        assertEquals("RESTORED", File(sd, "Saves/SNES/Zelda.srm").readText())
    }

    @Test fun `empty download does not overwrite the local save`() = runBlocking {
        writeSave("KEEP-ME")
        seedAnchor(lastUploadedHash = "old", localContentHash = "old")
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(SyncOperationDto(action = "download", romId = 42, saveId = 100, fileName = "Zelda.srm", slot = "autosave", serverUpdatedAt = "t", serverContentHash = "abc")),
            totalDownload = 1,
        )
        // relaxed downloadSaveContent writes nothing -> empty temp file
        val summary = service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(0, summary.downloaded)
        assertEquals("KEEP-ME", File(sd, "Saves/SNES/Zelda.srm").readText())
    }

    @Test fun `hash mismatch does not overwrite the local save`() = runBlocking {
        writeSave("KEEP-ME")
        seedAnchor(lastUploadedHash = "old", localContentHash = "old")
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(SyncOperationDto(action = "download", romId = 42, saveId = 100, fileName = "Zelda.srm", slot = "autosave", serverUpdatedAt = "t", serverContentHash = "0".repeat(32))),
            totalDownload = 1,
        )
        every { client.downloadSaveContent(any(), any(), any()) } answers { thirdArg<File>().writeBytes("WRONG".toByteArray()) }

        val summary = service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(0, summary.downloaded)
        assertEquals("KEEP-ME", File(sd, "Saves/SNES/Zelda.srm").readText())
    }

    @Test fun `matching hash applies the download`() = runBlocking {
        writeSave("KEEP-ME")
        seedAnchor(lastUploadedHash = "old", localContentHash = "old")
        val serverBytes = "SERVER-SAVE".toByteArray()
        val serverHash = SaveHasher.md5Hex(serverBytes)
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(SyncOperationDto(action = "download", romId = 42, saveId = 100, fileName = "Zelda.srm", slot = "autosave", serverUpdatedAt = "t", serverContentHash = serverHash)),
            totalDownload = 1,
        )
        every { client.downloadSaveContent(any(), any(), any()) } answers { thirdArg<File>().writeBytes(serverBytes) }

        val summary = service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(1, summary.downloaded)
        assertEquals("SERVER-SAVE", File(sd, "Saves/SNES/Zelda.srm").readText())
    }

    @Test fun `sweep escalates a non-convergent upload instead of looping`() = runBlocking {
        writeSave("LOCAL")
        val localHash = SaveHasher.hashFile(File(sd, "Saves/SNES/Zelda.srm"))
        // We already uploaded exactly this content, but the server's newest is different -> stuck.
        seedAnchor(lastUploadedHash = localHash, localContentHash = localHash)
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(
                SyncOperationDto(
                    action = "upload",
                    romId = 42,
                    saveId = 100,
                    fileName = "Zelda.srm",
                    slot = "autosave",
                    serverUpdatedAt = "2026-07-13T04:42:48Z",
                    serverContentHash = "different-server-head",
                    reason = "Client save is newer than last sync",
                )
            ),
            totalUpload = 1,
        )

        val summary = service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(0, summary.uploaded)
        assertEquals(1, pendingStore.count())
        assertEquals(SyncDirection.CONFLICT, historyStore.recent().first().direction)
        verify(exactly = 0) { client.uploadSave(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `sweep still uploads when an upload verdict is genuinely new content`() = runBlocking {
        writeSave("BRAND-NEW")
        val localHash = SaveHasher.hashFile(File(sd, "Saves/SNES/Zelda.srm"))
        // local differs from what we last uploaded -> real new content, upload converges normally.
        seedAnchor(lastUploadedHash = "old-uploaded", localContentHash = "old-local")
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(
            sessionId = 1,
            operations = listOf(
                SyncOperationDto(
                    action = "upload",
                    romId = 42,
                    saveId = 100,
                    fileName = "Zelda.srm",
                    slot = "autosave",
                    serverUpdatedAt = "t",
                    serverContentHash = "some-server-hash",
                    reason = "Client save is newer than last sync",
                )
            ),
            totalUpload = 1,
        )
        every { client.uploadSave(any(), any(), any(), any(), any(), any()) } returns RommSaveDto(id = 55, slot = "autosave", contentHash = localHash, updatedAt = "t2")

        val summary = service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(1, summary.uploaded)
        assertEquals(0, pendingStore.count())
    }

    @Test fun `upload 409 with a different server save escalates a conflict`() = runBlocking {
        writeSave("LOCAL")
        seedAnchor(lastUploadedHash = "old", localContentHash = "old") // local changed -> plan UPLOAD
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(sessionId = 1, operations = emptyList())
        every { client.uploadSave(any(), any(), any(), any(), any(), any()) } throws dev.cannoli.scorza.romm.RommException(409, "HTTP 409 Conflict")
        every { client.getSaves(42, "dev-1") } returns listOf(
            RommSaveDto(id = 88, romId = 42, slot = "autosave", contentHash = "different-server-hash", updatedAt = "t")
        )

        service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(1, pendingStore.count())
        assertEquals(SyncDirection.CONFLICT, historyStore.recent().first().direction)
    }

    @Test fun `upload 409 with an identical server save reconciles without conflict`() = runBlocking {
        writeSave("LOCAL")
        val localHash = SaveHasher.hashFile(File(sd, "Saves/SNES/Zelda.srm"))
        seedAnchor(lastUploadedHash = "old", localContentHash = "old") // local changed -> plan UPLOAD
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(sessionId = 1, operations = emptyList())
        every { client.uploadSave(any(), any(), any(), any(), any(), any()) } throws dev.cannoli.scorza.romm.RommException(409, "HTTP 409 Conflict")
        every { client.getSaves(42, "dev-1") } returns listOf(
            RommSaveDto(id = 88, romId = 42, slot = "autosave", contentHash = localHash, updatedAt = "2026-06-26T05:00:00Z")
        )

        val summary = service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(0, pendingStore.count())
        assertEquals(0, summary.uploaded)
        assertEquals(88, store.get("SNES/Zelda.sfc", DEFAULT_SLOT)?.rommSaveId)
        assertEquals(0, historyStore.recent().count { it.direction == SyncDirection.ERROR })
    }

    @Test fun `sweep promotes a pending restore when the server head is unchanged`() = runBlocking {
        writeSave("RESTORED")
        val localHash = SaveHasher.hashFile(File(sd, "Saves/SNES/Zelda.srm"))
        promotionStore.upsert(RestorePromotion("SNES/Zelda.sfc", DEFAULT_SLOT, localHash, baseHead = "server-head", System.currentTimeMillis()))
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(sessionId = 1, operations = emptyList())
        every { client.getSaves(42, "dev-1") } returns listOf(
            RommSaveDto(id = 200, romId = 42, slot = "autosave", contentHash = "server-head", updatedAt = "2026-07-13T04:42:48+00:00")
        )
        every { client.uploadSave(any(), any(), any(), any(), any(), any()) } returns RommSaveDto(id = 201, slot = "autosave", contentHash = localHash, updatedAt = "2026-07-15T00:00:00+00:00")

        val summary = service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        verify { client.uploadSave(42, "snes9x", "autosave", "dev-1", true, any()) }
        assertEquals(1, summary.uploaded)
        assertEquals(null, promotionStore.get("SNES/Zelda.sfc", DEFAULT_SLOT))
        assertEquals(0, pendingStore.count())
    }

    @Test fun `sweep escalates a pending restore when the server head moved`() = runBlocking {
        writeSave("RESTORED")
        val localHash = SaveHasher.hashFile(File(sd, "Saves/SNES/Zelda.srm"))
        promotionStore.upsert(RestorePromotion("SNES/Zelda.sfc", DEFAULT_SLOT, localHash, baseHead = "old-head", System.currentTimeMillis()))
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(sessionId = 1, operations = emptyList())
        every { client.getSaves(42, "dev-1") } returns listOf(
            RommSaveDto(id = 200, romId = 42, slot = "autosave", contentHash = "new-head-from-other-device", updatedAt = "2026-07-14T00:00:00+00:00")
        )

        service.sweep(resolveGame = { Triple("SNES", "Zelda", "snes9x") }, online = true)

        assertEquals(null, promotionStore.get("SNES/Zelda.sfc", DEFAULT_SLOT))
        assertEquals(1, pendingStore.count())
        assertEquals(SyncDirection.CONFLICT, historyStore.recent().first().direction)
        verify(exactly = 0) { client.uploadSave(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `restore promotes the restored save to head`() = runBlocking {
        writeSave("OLD-SAVE")
        backupManager.backup("SNES", "Zelda", 5, 1000L)
        writeSave("NEW-SAVE")
        seedAnchor(lastUploadedHash = "server-head", localContentHash = "new-save-hash")
        every { client.getSaves(42, "dev-1") } returns listOf(
            RommSaveDto(id = 200, romId = 42, slot = "autosave", contentHash = "server-head", updatedAt = "2026-07-13T00:00:00+00:00")
        )
        every { client.uploadSave(any(), any(), any(), any(), any(), any()) } returns RommSaveDto(id = 201, slot = "autosave", contentHash = SaveHasher.md5Hex("OLD-SAVE".toByteArray()), updatedAt = "2026-07-15T00:00:00+00:00")

        val outcome = service.restoreBackupToHead("SNES", "Zelda", 1000L) { Triple("SNES", "Zelda", "snes9x") }

        assertEquals(dev.cannoli.scorza.romm.sync.RestoreOutcome.Promoted, outcome)
        assertEquals("OLD-SAVE", File(sd, "Saves/SNES/Zelda.srm").readText())
        verify { client.uploadSave(42, "snes9x", "autosave", "dev-1", true, any()) }
        assertEquals(null, promotionStore.get("SNES/Zelda.sfc", DEFAULT_SLOT))
    }

    @Test fun `restore defers promotion when offline`() = runBlocking {
        writeSave("OLD-SAVE")
        backupManager.backup("SNES", "Zelda", 5, 2000L)
        writeSave("NEW-SAVE")
        seedAnchor(lastUploadedHash = "server-head", localContentHash = "new-save-hash")
        every { client.getSaves(42, "dev-1") } throws dev.cannoli.scorza.romm.RommException(null, "offline")

        val outcome = service.restoreBackupToHead("SNES", "Zelda", 2000L) { Triple("SNES", "Zelda", "snes9x") }

        assertEquals(dev.cannoli.scorza.romm.sync.RestoreOutcome.PendingPromote, outcome)
        assertEquals("OLD-SAVE", File(sd, "Saves/SNES/Zelda.srm").readText())
        val pending = promotionStore.get("SNES/Zelda.sfc", DEFAULT_SLOT)
        assertEquals(SaveHasher.md5Hex("OLD-SAVE".toByteArray()), pending?.targetHash)
        assertEquals("server-head", pending?.baseHead)
    }
}
