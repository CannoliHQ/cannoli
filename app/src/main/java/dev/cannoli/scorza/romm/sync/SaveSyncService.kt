package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.RommCapabilities
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.ui.components.SaveSyncStatus
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
        val serverContentHash: String? = null,
        val reason: String? = null,
    ) : PreLaunchOutcome
    data class KnownStaleBlock(val gameKey: String, val slot: String) : PreLaunchOutcome
}

sealed interface RestoreOutcome {
    object Failed : RestoreOutcome
    object RestoredLocalOnly : RestoreOutcome
    object Promoted : RestoreOutcome
    object Escalated : RestoreOutcome
    object PendingPromote : RestoreOutcome
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
    private val history: SyncHistoryStore,
    private val pendingConflicts: PendingConflictStore,
    private val promotions: RestorePromotionStore,
    private val statusHolder: SaveSyncStatusHolder,
    private val matcher: RommCacheMatcher,
    private val roms: dev.cannoli.scorza.db.RomsRepository,
) {
    private val paths: CannoliPaths get() = CannoliPaths(pathsProvider.root)


    fun deviceIdOrNull(): String? = registrar.deviceId()

    fun syncEnabled(): Boolean = settings.rommSaveSyncEnabled && connStore.isConfigured

    fun isSyncableGame(gameKey: String): Int? {
        if (!syncEnabled()) return null
        if (!RommCapabilities.isSupported(connStore.serverVersion)) return null
        if (registrar.deviceId().isNullOrEmpty()) return null
        links.rommIdForPath(gameKey)?.let { return it }
        val tag = gameKey.substringBefore('/')
        val fileName = java.io.File(gameKey).name
        return matcher.rommIdFor(tag, fileName)
    }

    private fun negotiate(romId: Int, slot: String, emulator: String?, local: LocalSave, anchor: SaveSyncRow?, deviceId: String): SyncNegotiateResponse? = try {
        client.negotiateSync(
            SyncNegotiatePayload(
                deviceId = deviceId,
                saves = listOf(
                    ClientSaveState(
                        romId = romId,
                        fileName = local.uploadFileName,
                        slot = slot,
                        emulator = emulator,
                        contentHash = anchor?.lastUploadedHash,
                        updatedAt = isoOf(local.modifiedMillis),
                        fileSizeBytes = local.sizeBytes,
                    )
                ),
            )
        )
    } catch (t: Throwable) {
        null
    }

    // RomM keeps every save as its own append-only row, so the newest one is the head. Ordering by
    // id breaks ties when a server hands back timestamps we cannot parse.
    private fun headSaveFor(saves: List<RommSaveDto>, slot: String): RommSaveDto? =
        saves.filter { (it.slot ?: DEFAULT_SLOT) == slot }
            .maxWithOrNull(compareBy({ savedAtMillis(it.updatedAt) }, { it.id }))

    private fun savedAtMillis(updatedAt: String): Long = try {
        java.time.OffsetDateTime.parse(updatedAt).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try { Instant.parse(updatedAt).toEpochMilli() } catch (_: Exception) { 0L }
    }

    private fun buildConflict(op: SyncOperationDto, local: LocalSave?, slot: String, romId: Int, tag: String, base: String, gameKey: String, emulator: String?): PreLaunchOutcome.Conflict =
        PreLaunchOutcome.Conflict(
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
            serverContentHash = op.serverContentHash,
            reason = op.reason.ifEmpty { null },
        )

    // A negotiate "upload" verdict we cannot satisfy: we already pushed exactly this content
    // (anchor == local) yet the server's newest save is different. RomM's overwrite=false upload
    // dedups our bytes back onto the old row, so re-uploading never converges. Treat it as a conflict.
    private fun isStuckUpload(op: SyncOperationDto, anchor: SaveSyncRow?, local: LocalSave): Boolean =
        anchor?.lastUploadedHash == local.contentHash &&
            op.serverContentHash != null &&
            op.serverContentHash != local.contentHash

    suspend fun syncBeforeLaunch(tag: String, base: String, gameKey: String, emulator: String?): PreLaunchOutcome =
        withContext(Dispatchers.IO) {
            val romId = isSyncableGame(gameKey) ?: return@withContext PreLaunchOutcome.Proceed
            val deviceId = registrar.deviceId() ?: return@withContext PreLaunchOutcome.Proceed
            val slot = store.activeSlot(gameKey)
            val local = resolver.resolve(tag, base)
            val anchor = store.get(gameKey, slot)
            if (local == null) {
                // No local save: if we previously synced this game, the local copy was deleted -> pull it back.
                return@withContext if (anchor != null) {
                    regenerateFromServer(tag, base, gameKey, slot, romId, deviceId)
                } else {
                    PreLaunchOutcome.Proceed
                }
            }
            val response = negotiate(romId, slot, emulator, local, anchor, deviceId)
                ?: return@withContext PreLaunchOutcome.Proceed
            val op = response.operations.firstOrNull { (it.slot ?: DEFAULT_SLOT) == slot }
            dev.cannoli.scorza.util.RommLog.write("launch [$base]: negotiate slot=$slot op=${op?.action ?: "none"} serverHash=${op?.serverContentHash?.take(8)} anchorHash=${anchor?.lastUploadedHash?.take(8)}")
            val outcome = when (op?.action) {
                "download" -> downloadOp(tag, base, gameKey, slot, romId, deviceId, op).also {
                    if (it == PreLaunchOutcome.Proceed) {
                        dev.cannoli.scorza.util.RommLog.write("launch [$base]: downloaded server save")
                    }
                }
                "upload" -> if (isStuckUpload(op, anchor, local)) {
                    dev.cannoli.scorza.util.RommLog.write("launch [$base]: upload cannot converge, surfacing conflict")
                    buildConflict(op, local, slot, romId, tag, base, gameKey, emulator)
                } else {
                    try {
                        uploadActive(tag, base, gameKey, slot, romId, emulator, deviceId, overwrite = false)
                        dev.cannoli.scorza.util.RommLog.write("launch [$base]: uploaded local save")
                    } catch (t: Throwable) {
                        val code = (t as? dev.cannoli.scorza.romm.RommException)?.statusCode
                        dev.cannoli.scorza.util.RommLog.write("launch [$base]: upload failed ${code ?: t.message}")
                    }
                    PreLaunchOutcome.Proceed
                }
                "conflict" -> buildConflict(op, local, slot, romId, tag, base, gameKey, emulator)
                else -> PreLaunchOutcome.Proceed
            }
            val completed = if (outcome == PreLaunchOutcome.Proceed && (op?.action == "download" || op?.action == "upload")) 1 else 0
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
        statusHolder.setActive(SaveSyncStatus.DOWNLOADING)
        val tmp = File.createTempFile("romm-save", ".bin", paths.configCache.apply { mkdirs() })
        return try {
            client.downloadSaveContent(saveId, deviceId, tmp)
            verifyDownloaded(tmp, op.serverContentHash)
            backupBeforeDownload(tag, base)
            resolver.applyDownload(tag, base, tmp)
            val confirmed = runCatching { client.confirmSaveDownloaded(saveId, deviceId) }.getOrNull()
            val hash = resolver.resolve(tag, base)?.contentHash
            // We verified non-empty bytes a moment ago, so an empty read-back means the storage
            // handed us stale or partial content. Recording it would anchor the game to a hash the
            // save does not have and make every later sweep call it up to date.
            if (hash == SaveHasher.EMPTY_MD5) {
                dev.cannoli.scorza.util.RommLog.write("download aborted [$base]: applied save reads back empty, anchor left untouched")
                return PreLaunchOutcome.KnownStaleBlock(gameKey, slot)
            }
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
            dev.cannoli.scorza.util.RommLog.write("download aborted [$base]: ${t.message}")
            PreLaunchOutcome.KnownStaleBlock(gameKey, slot)
        } finally {
            tmp.delete()
        }
    }

    private fun regenerateFromServer(
        tag: String,
        base: String,
        gameKey: String,
        slot: String,
        romId: Int,
        deviceId: String,
    ): PreLaunchOutcome {
        val serverSaves = try {
            client.getSaves(romId, deviceId)
        } catch (t: Throwable) {
            return PreLaunchOutcome.Proceed
        }
        val save = headSaveFor(serverSaves, slot) ?: return PreLaunchOutcome.Proceed
        val outcome = downloadOp(tag, base, gameKey, slot, romId, deviceId, downloadOpFor(save, romId, slot))
        if (outcome == PreLaunchOutcome.Proceed) {
            dev.cannoli.scorza.util.RommLog.write("launch [$base]: regenerated save (local was deleted)")
        }
        return outcome
    }

    suspend fun applyConflictUseServer(c: PreLaunchOutcome.Conflict, deviceId: String) = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("romm-save", ".bin", paths.configCache.apply { mkdirs() })
        try {
            client.downloadSaveContent(c.saveId, deviceId, tmp)
            verifyDownloaded(tmp, c.serverContentHash)
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

    fun listBackupGames(): List<SaveBackupGame> = backupManager.listGames()
    fun listBackups(tag: String, base: String): List<SaveBackup> = backupManager.list(tag, base)
    fun hasBackups(): Boolean = backupManager.listGames().isNotEmpty()
    fun restoreBackup(tag: String, base: String, stamp: Long): Boolean =
        backupManager.restore(tag, base, stamp, settings.rommSaveBackupCount)

    // Restore a backup locally and make it the server head. A restore is an explicit "this is my
    // save" intent, so it wins on the server too (append-only history keeps the superseded save).
    // Online: promote now (or escalate if the server head moved since restore). Offline: record a
    // deferred promotion the next sweep applies.
    suspend fun restoreBackupToHead(
        tag: String,
        base: String,
        stamp: Long,
        resolveGame: (String) -> Triple<String, String, String?>?,
    ): RestoreOutcome = withContext(Dispatchers.IO) {
        if (!restoreBackup(tag, base, stamp)) return@withContext RestoreOutcome.Failed
        val gameKey = findGameKey(tag, base, resolveGame) ?: return@withContext RestoreOutcome.RestoredLocalOnly
        val romId = isSyncableGame(gameKey) ?: return@withContext RestoreOutcome.RestoredLocalOnly
        val deviceId = registrar.deviceId() ?: return@withContext RestoreOutcome.RestoredLocalOnly
        val emulator = resolveGame(gameKey)?.third
        val slot = store.activeSlot(gameKey)
        val local = resolver.resolve(tag, base) ?: return@withContext RestoreOutcome.RestoredLocalOnly
        val baseHead = store.get(gameKey, slot)?.lastUploadedHash
        when (applyPromotion(tag, base, gameKey, slot, romId, emulator, local.contentHash, baseHead, deviceId)) {
            PromoteResult.PROMOTED -> { promotions.delete(gameKey, slot); RestoreOutcome.Promoted }
            PromoteResult.ESCALATED -> { promotions.delete(gameKey, slot); RestoreOutcome.Escalated }
            PromoteResult.UNREACHABLE -> {
                promotions.upsert(RestorePromotion(gameKey, slot, local.contentHash, baseHead, System.currentTimeMillis()))
                RestoreOutcome.PendingPromote
            }
        }
    }

    private enum class PromoteResult { PROMOTED, ESCALATED, UNREACHABLE }

    // Force the local save to become the server head, unless another device changed the head since
    // the restore (baseHead) - then surface a conflict so the user chooses. Returns UNREACHABLE when
    // the server can't be reached, so the caller can defer.
    private fun applyPromotion(
        tag: String,
        base: String,
        gameKey: String,
        slot: String,
        romId: Int,
        emulator: String?,
        targetHash: String,
        baseHead: String?,
        deviceId: String,
    ): PromoteResult {
        val head = try {
            headSaveFor(client.getSaves(romId, deviceId), slot)
        } catch (t: Throwable) {
            return PromoteResult.UNREACHABLE
        }
        val serverHead = head?.contentHash
        if (serverHead != null && serverHead == targetHash) {
            // Server already holds the restored content as head; adopt it as the anchor.
            store.upsert(
                SaveSyncRow(
                    gameKey = gameKey,
                    slot = slot,
                    rommRomId = romId,
                    rommSaveId = head.id,
                    lastSyncedAt = head.updatedAt,
                    lastUploadedHash = serverHead,
                    localContentHash = targetHash,
                    serverUpdatedAt = head.updatedAt,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return PromoteResult.PROMOTED
        }
        // Promote when the server head is unchanged since the restore (or the server has no save).
        if (serverHead == null || serverHead == baseHead) {
            return try {
                uploadActive(tag, base, gameKey, slot, romId, emulator, deviceId, overwrite = true)
                PromoteResult.PROMOTED
            } catch (t: Throwable) {
                PromoteResult.UNREACHABLE
            }
        }
        // Server moved to a different head since the restore: let the user choose.
        val conflict = PreLaunchOutcome.Conflict(
            gameKey = gameKey,
            slot = slot,
            localTime = null,
            serverTime = head.updatedAt,
            serverDevice = head.originDeviceId,
            saveId = head.id,
            romId = romId,
            tag = tag,
            base = base,
            emulator = emulator,
            serverContentHash = serverHead,
            reason = "server changed since restore",
        )
        escalate(gameKey, base, conflict)
        return PromoteResult.ESCALATED
    }

    private fun findGameKey(tag: String, base: String, resolveGame: (String) -> Triple<String, String, String?>?): String? =
        roms.allRelativePaths().firstOrNull { gk ->
            val r = resolveGame(gk) ?: return@firstOrNull false
            r.first == tag && r.second == base
        }

    // Guard the local save from a bad download: never overwrite with an empty file, and when the
    // server gives us an md5 content hash, require the bytes to match before we apply them.
    private fun verifyDownloaded(tmp: File, expectedHash: String?) {
        if (tmp.length() == 0L) throw IllegalStateException("empty download")
        if (expectedHash != null && expectedHash.length == 32) {
            val actual = SaveHasher.hashFile(tmp)
            if (!actual.equals(expectedHash, ignoreCase = true)) {
                throw IllegalStateException("hash mismatch (expected $expectedHash, got $actual)")
            }
        }
    }

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
        statusHolder.setActive(SaveSyncStatus.UPLOADING)
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

    fun pendingConflictCount(): Int = pendingConflicts.count()

    fun localSaveModifiedMillis(tag: String, base: String): Long? = resolver.resolve(tag, base)?.modifiedMillis

    fun clearResolvedConflict(gameKey: String, displayName: String, keepLocal: Boolean) {
        pendingConflicts.delete(gameKey)
        history.add(entry(gameKey, displayName, if (keepLocal) SyncDirection.UPLOAD else SyncDirection.DOWNLOAD))
    }

    private enum class SweepAction(val label: String) {
        UP_TO_DATE("up to date"),
        UPLOAD("upload"),
        DOWNLOAD("download (server newer)"),
        REGENERATE("regenerate (local deleted)"),
        KEEP_LOCAL("conflict, keep local"),
        KEEP_SERVER("conflict, keep server"),
        ESCALATE("conflict, needs manual resolve"),
        PROMOTE("promote restored save to head"),
        NO_SAVE("no save"),
        UNREACHABLE("server unreachable"),
    }

    private class SweepPlan(
        val tag: String,
        val name: String,
        val gameKey: String,
        val slot: String,
        val emulator: String?,
        val romId: Int,
        val action: SweepAction,
        val downloadOp: SyncOperationDto? = null,
        val conflict: PreLaunchOutcome.Conflict? = null,
        val debug: String? = null,
        val promotion: RestorePromotion? = null,
    )

    private class ExecResult(val direction: SyncDirection?, val ok: Boolean, val label: String, val logDetail: String? = null)

    private class Scanned(
        val tag: String,
        val base: String,
        val gameKey: String,
        val slot: String,
        val emulator: String?,
        val romId: Int,
        val local: LocalSave?,
        val anchor: SaveSyncRow?,
    )

    suspend fun sweep(
        resolveGame: (gameKey: String) -> Triple<String, String, String?>?,
    ): SyncSummary = withContext(Dispatchers.IO) {
        val deviceId = registrar.deviceId()
        if (deviceId == null) {
            statusHolder.settle(enabled = syncEnabled(), online = true, pendingConflicts = pendingConflicts.count(), hadError = false)
            return@withContext SyncSummary(0, 0, 0)
        }
        matcher.refresh()
        val gameKeys = roms.allRelativePaths()

        // Phase 1a: scan - resolve each associable game's local save + sync anchor.
        dev.cannoli.scorza.util.RommLog.write("=== sweep: scanning ${gameKeys.size} local games ===")
        val scanned = ArrayList<Scanned>()
        for (gameKey in gameKeys) {
            val (tag, base, emulator) = resolveGame(gameKey) ?: continue
            val romId = isSyncableGame(gameKey) ?: continue
            val slot = store.activeSlot(gameKey)
            scanned.add(Scanned(tag, base, gameKey, slot, emulator, romId, resolver.resolve(tag, base), store.get(gameKey, slot)))
        }

        // Phase 1b: one batch negotiate for every game that has a local save.
        val withLocal = scanned.filter { it.local != null }
        val opByKey = HashMap<Pair<Int, String>, SyncOperationDto>()
        var batchReached = false
        var batchFailed = false
        if (withLocal.isNotEmpty()) {
            dev.cannoli.scorza.util.RommLog.write("=== sweep: negotiating ${withLocal.size} saves with RomM ===")
            val payload = SyncNegotiatePayload(
                deviceId = deviceId,
                saves = withLocal.map { s ->
                    ClientSaveState(
                        romId = s.romId,
                        fileName = s.local!!.uploadFileName,
                        slot = s.slot,
                        emulator = s.emulator,
                        contentHash = s.anchor?.lastUploadedHash,
                        updatedAt = isoOf(s.local.modifiedMillis),
                        fileSizeBytes = s.local.sizeBytes,
                    )
                },
            )
            val startedAt = System.currentTimeMillis()
            val response = try {
                client.negotiateSync(payload)
            } catch (t: Throwable) {
                val elapsed = System.currentTimeMillis() - startedAt
                dev.cannoli.scorza.util.RommLog.write("sweep: negotiate failed after ${elapsed}ms: ${t.javaClass.simpleName} ${t.message}")
                null
            }
            if (response != null) {
                batchReached = true
                response.operations.forEach { opByKey[it.romId to (it.slot ?: DEFAULT_SLOT)] = it }
                runCatching {
                    val completed = response.operations.count { it.action == "download" || it.action == "upload" }
                    client.completeSyncSession(response.sessionId, SyncCompletePayload(operationsCompleted = completed, operationsFailed = 0))
                }
            } else {
                batchFailed = true
            }
        }

        // Phase 1c: decide each game's action (regenerate candidates query the server per-rom).
        var getSavesReached = false
        var getSavesAttempted = false
        val plans = scanned.map { s ->
            planFor(s, opByKey[s.romId to s.slot], batchFailed, deviceId) { ok ->
                getSavesAttempted = true
                if (ok) getSavesReached = true
            }
        }
        val reached = batchReached || getSavesReached
        val serverContacted = withLocal.isNotEmpty() || getSavesAttempted
        val attempted = scanned.count { it.local != null || it.anchor != null }

        // Phase 2: report the plan, sorted by platform then game name.
        val sorted = plans.sortedWith(compareBy({ it.tag }, { it.name.lowercase() }))
        dev.cannoli.scorza.util.RommLog.write("=== sweep: plan (${plans.size} associable games) ===")
        for (p in sorted) {
            dev.cannoli.scorza.util.RommLog.write("[${p.tag}] ${p.name} (slot=${p.slot}): ${p.action.label}${p.debug?.let { " | $it" } ?: ""}")
        }

        // Phase 3: apply the actionable plans.
        dev.cannoli.scorza.util.RommLog.write("=== sweep: applying ===")
        var up = 0; var down = 0; var conflicts = 0; var error = false
        val failures = ArrayList<SyncFailure>()
        for (p in sorted) {
            if (p.action == SweepAction.UP_TO_DATE || p.action == SweepAction.NO_SAVE || p.action == SweepAction.UNREACHABLE) continue
            val r = executePlan(p, deviceId)
            dev.cannoli.scorza.util.RommLog.write("[${p.tag}] ${p.name}: ${p.action.label} -> ${r.label}${r.logDetail?.let { " | $it" } ?: ""}")
            when (r.direction) {
                SyncDirection.UPLOAD -> if (r.ok) up++
                SyncDirection.DOWNLOAD -> if (r.ok) down++
                SyncDirection.CONFLICT -> conflicts++
                else -> {}
            }
            if (!r.ok) { error = true; failures.add(SyncFailure(p.name, r.label)) }
            // escalate() records conflicts itself (deduped); log only real transfers and errors here.
            when {
                !r.ok -> history.add(entry(p.gameKey, p.name, SyncDirection.ERROR, r.label))
                r.direction == SyncDirection.UPLOAD || r.direction == SyncDirection.DOWNLOAD ->
                    history.add(entry(p.gameKey, p.name, r.direction))
                else -> {}
            }
        }

        val pending = pendingConflicts.count()
        // Reachability reflects whether RomM actually answered, not whether Android
        // validated internet: a LAN-only server on an unvalidated network still syncs.
        val reachable = reached || !serverContacted
        statusHolder.setErrors(failures)
        statusHolder.settle(enabled = syncEnabled(), online = reachable, pendingConflicts = pending, hadError = error)
        dev.cannoli.scorza.util.RommLog.write("=== sweep done: up=$up down=$down conflicts=$conflicts attempted=$attempted reachable=$reachable pending=$pending status=${statusHolder.state.value} error=$error ===")
        if (statusHolder.state.value == SaveSyncStatus.OFFLINE) {
            dev.cannoli.scorza.util.RommLog.write("=== sweep: OFFLINE (RomM server unreachable) reached=$reached contacted=$serverContacted attempted=$attempted ===")
        }
        SyncSummary(up, down, conflicts)
    }

    private fun planFor(s: Scanned, op: SyncOperationDto?, batchFailed: Boolean, deviceId: String, onReach: (Boolean) -> Unit): SweepPlan {
        val dbg = "local=${s.local?.contentHash?.take(8)} anchorUp=${s.anchor?.lastUploadedHash?.take(8)} " +
            "anchorLocal=${s.anchor?.localContentHash?.take(8)} server=${op?.serverContentHash?.take(8)}" +
            (op?.reason?.ifEmpty { null }?.let { " reason=$it" } ?: "")
        fun plan(action: SweepAction, downloadOp: SyncOperationDto? = null, conflict: PreLaunchOutcome.Conflict? = null, promotion: RestorePromotion? = null) =
            SweepPlan(s.tag, s.base, s.gameKey, s.slot, s.emulator, s.romId, action, downloadOp, conflict, dbg, promotion)
        val local = s.local
        if (local == null) {
            // No local save: pull the server copy if one exists, even with no prior anchor
            // (first-time restore / restore after a local delete).
            val serverSaves = try {
                client.getSaves(s.romId, deviceId).also { onReach(true) }
            } catch (t: Throwable) {
                onReach(false)
                return plan(SweepAction.UNREACHABLE)
            }
            val save = headSaveFor(serverSaves, s.slot) ?: return plan(SweepAction.NO_SAVE)
            val action = if (s.anchor == null) SweepAction.DOWNLOAD else SweepAction.REGENERATE
            return plan(action, downloadOp = downloadOpFor(save, s.romId, s.slot))
        }
        promotions.get(s.gameKey, s.slot)?.let { return plan(SweepAction.PROMOTE, promotion = it) }
        if (batchFailed) return plan(SweepAction.UNREACHABLE)
        return when (op?.action) {
            "download" -> plan(SweepAction.DOWNLOAD, downloadOp = op)
            "upload" -> if (isStuckUpload(op, s.anchor, local)) {
                plan(SweepAction.ESCALATE, conflict = buildConflict(op, local, s.slot, s.romId, s.tag, s.base, s.gameKey, s.emulator))
            } else {
                plan(SweepAction.UPLOAD)
            }
            "conflict" -> {
                val cf = buildConflict(op, local, s.slot, s.romId, s.tag, s.base, s.gameKey, s.emulator)
                when (ConflictAutoResolver.classify(local.contentHash, s.anchor?.localContentHash, op.serverContentHash, s.anchor?.lastUploadedHash)) {
                    ConflictResolution.KEEP_LOCAL -> plan(SweepAction.KEEP_LOCAL, conflict = cf)
                    ConflictResolution.KEEP_SERVER -> plan(SweepAction.KEEP_SERVER, conflict = cf)
                    ConflictResolution.ESCALATE -> plan(SweepAction.ESCALATE, conflict = cf)
                }
            }
            else -> {
                val anchor = s.anchor
                when {
                    anchor == null -> plan(SweepAction.UPLOAD)
                    anchor.localContentHash == local.contentHash -> plan(SweepAction.UP_TO_DATE)
                    // A real save never hashes empty, so this anchor was written from a bad read and
                    // we cannot tell what the file next to it is. Let the user choose rather than
                    // pushing content we cannot vouch for over the server head.
                    anchor.localContentHash == SaveHasher.EMPTY_MD5 -> driftPlan(s, deviceId, onReach, ::plan)
                    else -> plan(SweepAction.UPLOAD)
                }
            }
        }
    }

    private fun driftPlan(
        s: Scanned,
        deviceId: String,
        onReach: (Boolean) -> Unit,
        plan: (SweepAction, SyncOperationDto?, PreLaunchOutcome.Conflict?, RestorePromotion?) -> SweepPlan,
    ): SweepPlan {
        val head = try {
            headSaveFor(client.getSaves(s.romId, deviceId), s.slot).also { onReach(true) }
        } catch (t: Throwable) {
            onReach(false)
            return plan(SweepAction.UNREACHABLE, null, null, null)
        } ?: return plan(SweepAction.UPLOAD, null, null, null)
        val conflict = PreLaunchOutcome.Conflict(
            gameKey = s.gameKey,
            slot = s.slot,
            localTime = s.local?.let { isoOf(it.modifiedMillis) },
            serverTime = head.updatedAt,
            serverDevice = head.originDeviceId,
            saveId = head.id,
            romId = s.romId,
            tag = s.tag,
            base = s.base,
            emulator = s.emulator,
            serverContentHash = head.contentHash,
            reason = "local save does not match its sync record",
        )
        return plan(SweepAction.ESCALATE, null, conflict, null)
    }

    private suspend fun executePlan(p: SweepPlan, deviceId: String): ExecResult = when (p.action) {
        SweepAction.UPLOAD -> {
            statusHolder.setActive(SaveSyncStatus.UPLOADING)
            try {
                uploadActive(p.tag, p.name, p.gameKey, p.slot, p.romId, p.emulator, deviceId, overwrite = false)
                ExecResult(SyncDirection.UPLOAD, true, "uploaded")
            } catch (t: Throwable) {
                // A 409 means the server already has a save we would clobber -> that is a conflict,
                // not a hard error. Reconcile if identical, otherwise escalate for manual resolve.
                if ((t as? dev.cannoli.scorza.romm.RommException)?.statusCode == 409) resolveUploadConflict(p, deviceId)
                else ExecResult(SyncDirection.UPLOAD, false, "upload failed (${errLabel(t)})", logDetailOf(t))
            }
        }
        SweepAction.DOWNLOAD, SweepAction.REGENERATE -> {
            statusHolder.setActive(SaveSyncStatus.DOWNLOADING)
            val outcome = downloadOp(p.tag, p.name, p.gameKey, p.slot, p.romId, deviceId, p.downloadOp!!)
            if (outcome == PreLaunchOutcome.Proceed) {
                ExecResult(SyncDirection.DOWNLOAD, true, if (p.action == SweepAction.REGENERATE) "regenerated" else "downloaded")
            } else {
                ExecResult(SyncDirection.DOWNLOAD, false, "download failed")
            }
        }
        SweepAction.KEEP_LOCAL -> {
            statusHolder.setActive(SaveSyncStatus.UPLOADING)
            try {
                applyConflictKeepLocal(p.conflict!!, deviceId)
                pendingConflicts.delete(p.gameKey)
                ExecResult(SyncDirection.UPLOAD, true, "kept local")
            } catch (t: Throwable) {
                ExecResult(SyncDirection.UPLOAD, false, "keep-local failed (${errLabel(t)})", logDetailOf(t))
            }
        }
        SweepAction.KEEP_SERVER -> {
            statusHolder.setActive(SaveSyncStatus.DOWNLOADING)
            try {
                applyConflictUseServer(p.conflict!!, deviceId)
                pendingConflicts.delete(p.gameKey)
                ExecResult(SyncDirection.DOWNLOAD, true, "kept server")
            } catch (t: Throwable) {
                ExecResult(SyncDirection.DOWNLOAD, false, "keep-server failed (${errLabel(t)})", logDetailOf(t))
            }
        }
        SweepAction.ESCALATE -> {
            escalate(p.gameKey, p.name, p.conflict!!)
            ExecResult(SyncDirection.CONFLICT, true, "escalated for manual resolve")
        }
        SweepAction.PROMOTE -> {
            statusHolder.setActive(SaveSyncStatus.UPLOADING)
            val pr = p.promotion!!
            when (applyPromotion(p.tag, p.name, p.gameKey, p.slot, p.romId, p.emulator, pr.targetHash, pr.baseHead, deviceId)) {
                PromoteResult.PROMOTED -> { promotions.delete(p.gameKey, p.slot); ExecResult(SyncDirection.UPLOAD, true, "promoted restored save to head") }
                PromoteResult.ESCALATED -> { promotions.delete(p.gameKey, p.slot); ExecResult(SyncDirection.CONFLICT, true, "restore superseded by server") }
                PromoteResult.UNREACHABLE -> ExecResult(null, true, "promote deferred (offline)")
            }
        }
        else -> ExecResult(null, true, "skipped")
    }

    private fun resolveUploadConflict(p: SweepPlan, deviceId: String): ExecResult {
        val serverSaves = try {
            client.getSaves(p.romId, deviceId)
        } catch (t: Throwable) {
            return ExecResult(SyncDirection.UPLOAD, false, "upload failed (409; ${errLabel(t)})")
        }
        val save = headSaveFor(serverSaves, p.slot)
            ?: return ExecResult(SyncDirection.UPLOAD, false, "upload failed (409)")
        val local = resolver.resolve(p.tag, p.name)
        if (local != null && save.contentHash != null && save.contentHash == local.contentHash) {
            // Server already holds an identical save; adopt it as the anchor so future sweeps are quiet.
            store.upsert(
                SaveSyncRow(
                    gameKey = p.gameKey,
                    slot = p.slot,
                    rommRomId = p.romId,
                    rommSaveId = save.id,
                    lastSyncedAt = save.updatedAt,
                    lastUploadedHash = save.contentHash,
                    localContentHash = local.contentHash,
                    serverUpdatedAt = save.updatedAt,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return ExecResult(null, true, "already in sync")
        }
        val conflict = PreLaunchOutcome.Conflict(
            gameKey = p.gameKey,
            slot = p.slot,
            localTime = local?.let { isoOf(it.modifiedMillis) },
            serverTime = save.updatedAt,
            serverDevice = save.originDeviceId,
            saveId = save.id,
            romId = p.romId,
            tag = p.tag,
            base = p.name,
            emulator = p.emulator,
            serverContentHash = save.contentHash,
            reason = "server already has a save",
        )
        escalate(p.gameKey, p.name, conflict)
        return ExecResult(SyncDirection.CONFLICT, true, "conflict (server has a save)")
    }

    private fun downloadOpFor(save: RommSaveDto, romId: Int, slot: String): SyncOperationDto =
        SyncOperationDto(
            action = "download",
            romId = romId,
            saveId = save.id,
            fileName = save.fileName,
            slot = slot,
            serverUpdatedAt = save.updatedAt,
            serverContentHash = save.contentHash,
        )

    private fun errLabel(t: Throwable): String {
        val code = (t as? dev.cannoli.scorza.romm.RommException)?.statusCode
        return if (code != null) "$code ${httpReason(code)}".trim() else (t.message ?: "error")
    }

    // The full server message (with response body) for the log only; null for non-HTTP errors,
    // whose reason errLabel already carries.
    private fun logDetailOf(t: Throwable): String? = (t as? dev.cannoli.scorza.romm.RommException)?.message

    private fun httpReason(code: Int): String = when (code) {
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Too Large"
        422 -> "Unprocessable"
        500 -> "Server Error"
        502 -> "Bad Gateway"
        503 -> "Unavailable"
        else -> ""
    }

    suspend fun resolvePending(gameKey: String, keepLocal: Boolean, resolveGame: (String) -> Triple<String, String, String?>?): Boolean = withContext(Dispatchers.IO) {
        val pc = pendingConflicts.get(gameKey) ?: return@withContext false
        val deviceId = registrar.deviceId() ?: return@withContext false
        val (tag, base, emulator) = resolveGame(gameKey) ?: return@withContext false
        val slot = store.activeSlot(gameKey)
        val c = PreLaunchOutcome.Conflict(
            gameKey, slot, null, pc.serverUpdatedAt, null, pc.serverSaveId ?: 0, pc.romId, tag, base, emulator,
            serverContentHash = pc.serverContentHash,
        )
        try {
            if (keepLocal) {
                applyConflictKeepLocal(c, deviceId)
                history.add(entry(gameKey, pc.displayName, SyncDirection.UPLOAD))
            } else {
                applyConflictUseServer(c, deviceId)
                history.add(entry(gameKey, pc.displayName, SyncDirection.DOWNLOAD))
            }
            pendingConflicts.delete(gameKey)
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
    }

    fun skipPending(gameKey: String) {
        val pc = pendingConflicts.get(gameKey) ?: return
        pendingConflicts.markDismissed(gameKey, pc.serverContentHash)
    }

    private fun entry(gameKey: String, name: String, dir: SyncDirection, detail: String? = null) =
        SyncHistoryEntry(gameKey, name, dir, detail, System.currentTimeMillis())

    private fun escalate(gameKey: String, name: String, c: PreLaunchOutcome.Conflict) {
        val existing = pendingConflicts.get(gameKey)
        // Already recorded this exact conflict (pending or dismissed) -> don't re-log it every sweep.
        if (existing != null && existing.serverContentHash == c.serverContentHash) return
        pendingConflicts.upsert(
            PendingConflict(gameKey, c.romId, name, c.saveId, c.serverContentHash, c.serverTime, System.currentTimeMillis(), null)
        )
        history.add(entry(gameKey, name, SyncDirection.CONFLICT, c.reason))
    }

    private fun isoOf(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
