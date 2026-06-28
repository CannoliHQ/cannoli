package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

sealed interface PreLaunchOutcome {
    object Proceed : PreLaunchOutcome
    data class Conflict(
        val gameKey: String,
        val slot: String,
        val localTime: String?,
        val serverTime: String?,
        val serverDevice: String?,
        val saveId: Int,
        val romId: Int,
        val tag: String,
        val base: String,
        val emulator: String?,
    ) : PreLaunchOutcome
    data class KnownStaleBlock(val gameKey: String, val slot: String) : PreLaunchOutcome
}

class SaveSyncService(
    private val client: RommClient,
    private val connStore: RommConnectionStore,
    private val settings: SettingsRepository,
    private val registrar: DeviceRegistrar,
    private val store: SaveSyncStore,
    private val resolver: LocalSaveResolver,
    private val links: RommLinkRepository,
    private val pathsProvider: CannoliPathsProvider,
    private val backupManager: SaveBackupManager,
) {
    private val paths: CannoliPaths get() = CannoliPaths(pathsProvider.root)

    fun deviceIdOrNull(): String? = registrar.deviceId()

    fun isSyncableGame(gameKey: String): Int? {
        if (!settings.rommSaveSyncEnabled) return null
        if (!connStore.isConfigured) return null
        if (!SaveSyncCapabilities.supportsSaveSync(connStore.serverVersion)) return null
        if (registrar.deviceId().isNullOrEmpty()) return null
        return links.rommIdForPath(gameKey)
    }

    suspend fun syncBeforeLaunch(tag: String, base: String, gameKey: String, emulator: String?): PreLaunchOutcome =
        withContext(Dispatchers.IO) {
            val romId = isSyncableGame(gameKey) ?: return@withContext PreLaunchOutcome.Proceed
            val deviceId = registrar.deviceId() ?: return@withContext PreLaunchOutcome.Proceed
            val slot = store.activeSlot(gameKey)
            val local = resolver.resolve(tag, base)
            val anchor = store.get(gameKey, slot)
            val response = try {
                client.negotiateSync(
                    SyncNegotiatePayload(
                        deviceId = deviceId,
                        saves = listOfNotNull(local?.let {
                            ClientSaveState(
                                romId = romId,
                                fileName = it.uploadFileName,
                                slot = slot,
                                emulator = emulator,
                                contentHash = anchor?.lastUploadedHash,
                                updatedAt = isoOf(it.modifiedMillis),
                                fileSizeBytes = it.sizeBytes,
                            )
                        }),
                    ),
                )
            } catch (t: Throwable) {
                return@withContext PreLaunchOutcome.Proceed
            }
            val op = response.operations.firstOrNull { (it.slot ?: DEFAULT_SLOT) == slot }
            val outcome = when (op?.action) {
                "download" -> downloadOp(tag, base, gameKey, slot, romId, deviceId, op)
                "conflict" -> PreLaunchOutcome.Conflict(
                    gameKey = gameKey,
                    slot = slot,
                    localTime = local?.let { isoOf(it.modifiedMillis) },
                    serverTime = op.serverUpdatedAt,
                    serverDevice = null,
                    saveId = op.saveId ?: 0,
                    romId = romId,
                    tag = tag,
                    base = base,
                    emulator = emulator,
                )
                else -> PreLaunchOutcome.Proceed
            }
            val completed = if (outcome == PreLaunchOutcome.Proceed && op?.action == "download") 1 else 0
            val failed = if (outcome is PreLaunchOutcome.KnownStaleBlock) 1 else 0
            runCatching { client.completeSyncSession(response.sessionId, SyncCompletePayload(operationsCompleted = completed, operationsFailed = failed)) }
            outcome
        }

    private fun downloadOp(
        tag: String,
        base: String,
        gameKey: String,
        slot: String,
        romId: Int,
        deviceId: String,
        op: SyncOperationDto,
    ): PreLaunchOutcome {
        val saveId = op.saveId ?: return PreLaunchOutcome.KnownStaleBlock(gameKey, slot)
        val tmp = File.createTempFile("romm-save", ".bin", paths.configCache.apply { mkdirs() })
        return try {
            client.downloadSaveContent(saveId, deviceId, tmp)
            backupBeforeDownload(tag, base)
            resolver.applyDownload(tag, base, tmp)
            val confirmed = runCatching { client.confirmSaveDownloaded(saveId, deviceId) }.getOrNull()
            val hash = resolver.resolve(tag, base)?.contentHash
            store.upsert(
                SaveSyncRow(
                    gameKey = gameKey,
                    slot = slot,
                    rommRomId = romId,
                    rommSaveId = saveId,
                    lastSyncedAt = op.serverUpdatedAt,
                    lastUploadedHash = op.serverContentHash ?: confirmed?.contentHash ?: hash,
                    localContentHash = hash,
                    serverUpdatedAt = op.serverUpdatedAt,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            PreLaunchOutcome.Proceed
        } catch (t: Throwable) {
            PreLaunchOutcome.KnownStaleBlock(gameKey, slot)
        } finally {
            tmp.delete()
        }
    }

    suspend fun applyConflictUseServer(c: PreLaunchOutcome.Conflict, deviceId: String) = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("romm-save", ".bin", paths.configCache.apply { mkdirs() })
        try {
            client.downloadSaveContent(c.saveId, deviceId, tmp)
            backupBeforeDownload(c.tag, c.base)
            resolver.applyDownload(c.tag, c.base, tmp)
            val confirmed = runCatching { client.confirmSaveDownloaded(c.saveId, deviceId) }.getOrNull()
            val hash = resolver.resolve(c.tag, c.base)?.contentHash
            store.upsert(
                SaveSyncRow(
                    gameKey = c.gameKey,
                    slot = c.slot,
                    rommRomId = c.romId,
                    rommSaveId = c.saveId,
                    lastSyncedAt = c.serverTime,
                    lastUploadedHash = confirmed?.contentHash ?: hash,
                    localContentHash = hash,
                    serverUpdatedAt = c.serverTime,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        } finally {
            tmp.delete()
        }
    }

    suspend fun applyConflictKeepLocal(c: PreLaunchOutcome.Conflict, deviceId: String) = withContext(Dispatchers.IO) {
        uploadActive(c.tag, c.base, c.gameKey, c.slot, c.romId, c.emulator, deviceId, overwrite = true)
    }

    fun backupBeforeDownload(tag: String, base: String) =
        backupManager.backup(tag, base, settings.rommSaveBackupCount, System.currentTimeMillis())

    internal fun uploadActive(
        tag: String,
        base: String,
        gameKey: String,
        slot: String,
        romId: Int,
        emulator: String?,
        deviceId: String,
        overwrite: Boolean,
    ) {
        val local = resolver.resolve(tag, base) ?: return
        val cacheDir = paths.configCache.apply { mkdirs() }
        val file = if (local.isBundle) {
            resolver.bundleToZip(tag, base, File.createTempFile("romm-up", ".zip", cacheDir))
        } else {
            local.files.single()
        }
        val saved = try {
            client.uploadSave(romId, emulator, slot, deviceId, overwrite, file)
        } finally {
            if (local.isBundle) file.delete()
        }
        store.upsert(
            SaveSyncRow(
                gameKey = gameKey,
                slot = slot,
                rommRomId = romId,
                rommSaveId = saved.id,
                lastSyncedAt = saved.updatedAt,
                lastUploadedHash = saved.contentHash ?: local.contentHash,
                localContentHash = local.contentHash,
                serverUpdatedAt = saved.updatedAt,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    data class SyncSummary(val uploaded: Int, val downloaded: Int, val conflicts: Int)

    suspend fun syncAfterExit(tag: String, base: String, gameKey: String, emulator: String?): Boolean = withContext(Dispatchers.IO) {
        val romId = isSyncableGame(gameKey) ?: return@withContext false
        val deviceId = registrar.deviceId() ?: return@withContext false
        val slot = store.activeSlot(gameKey)
        val local = resolver.resolve(tag, base) ?: return@withContext false
        val anchor = store.get(gameKey, slot)
        if (anchor?.localContentHash == local.contentHash) return@withContext false
        try {
            uploadActive(tag, base, gameKey, slot, romId, emulator, deviceId, overwrite = false)
            true
        } catch (e: dev.cannoli.scorza.romm.RommException) {
            if (e.statusCode == 409) return@withContext false
            false
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun syncAll(resolveGame: (gameKey: String) -> Triple<String, String, String?>?): SyncSummary = withContext(Dispatchers.IO) {
        var up = 0; var down = 0; var conflicts = 0
        for (gameKey in allLinkedGameKeys()) {
            val (tag, base, emulator) = resolveGame(gameKey) ?: continue
            val slot = store.activeSlot(gameKey)
            val saveIdBefore = store.get(gameKey, slot)?.rommSaveId
            when (syncBeforeLaunch(tag, base, gameKey, emulator)) {
                is PreLaunchOutcome.Conflict -> conflicts++
                is PreLaunchOutcome.KnownStaleBlock -> Unit
                PreLaunchOutcome.Proceed -> {
                    val saveIdAfter = store.get(gameKey, slot)?.rommSaveId
                    if (saveIdAfter != null && saveIdAfter != saveIdBefore) down++
                }
            }
            if (syncAfterExit(tag, base, gameKey, emulator)) up++
        }
        SyncSummary(up, down, conflicts)
    }

    private fun allLinkedGameKeys(): List<String> = links.allRelativePaths()

    private fun isoOf(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
