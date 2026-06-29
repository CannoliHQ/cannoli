package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
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
    private val statusHolder: SaveSyncStatusHolder,
    private val matcher: RommCacheMatcher,
    private val roms: dev.cannoli.scorza.db.RomsRepository,
) {
    private val paths: CannoliPaths get() = CannoliPaths(pathsProvider.root)


    fun deviceIdOrNull(): String? = registrar.deviceId()

    fun isSyncableGame(gameKey: String): Int? {
        if (!settings.rommSaveSyncEnabled) return null
        if (!connStore.isConfigured) return null
        if (!SaveSyncCapabilities.supportsSaveSync(connStore.serverVersion)) return null
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
                "upload" -> {
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
        val save = serverSaves.firstOrNull { (it.slot ?: DEFAULT_SLOT) == slot } ?: return PreLaunchOutcome.Proceed
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
    )

    private class ExecResult(val direction: SyncDirection?, val ok: Boolean, val label: String)

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
        online: Boolean,
    ): SyncSummary = withContext(Dispatchers.IO) {
        val deviceId = registrar.deviceId()
        if (deviceId == null) {
            statusHolder.settle(enabled = settings.rommSaveSyncEnabled, online = online, pendingConflicts = pendingConflicts.count(), hadError = false)
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
            val response = try { client.negotiateSync(payload) } catch (t: Throwable) { null }
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
        val plans = scanned.map { s ->
            planFor(s, opByKey[s.romId to s.slot], batchFailed, deviceId) { if (it) getSavesReached = true }
        }
        val reached = batchReached || getSavesReached
        val attempted = scanned.count { it.local != null || it.anchor != null }

        // Phase 2: report the plan, sorted by platform then game name.
        val sorted = plans.sortedWith(compareBy({ it.tag }, { it.name.lowercase() }))
        dev.cannoli.scorza.util.RommLog.write("=== sweep: plan (${plans.size} associable games) ===")
        for (p in sorted) {
            dev.cannoli.scorza.util.RommLog.write("[${p.tag}] ${p.name} (slot=${p.slot}): ${p.action.label}")
        }

        // Phase 3: apply the actionable plans.
        dev.cannoli.scorza.util.RommLog.write("=== sweep: applying ===")
        var up = 0; var down = 0; var conflicts = 0; var error = false
        for (p in sorted) {
            if (p.action == SweepAction.UP_TO_DATE || p.action == SweepAction.NO_SAVE || p.action == SweepAction.UNREACHABLE) continue
            val r = executePlan(p, deviceId)
            dev.cannoli.scorza.util.RommLog.write("[${p.tag}] ${p.name}: ${p.action.label} -> ${r.label}")
            when (r.direction) {
                SyncDirection.UPLOAD -> if (r.ok) up++
                SyncDirection.DOWNLOAD -> if (r.ok) down++
                SyncDirection.CONFLICT -> conflicts++
                else -> {}
            }
            if (!r.ok) error = true
            // escalate() records conflicts itself (deduped); log only real transfers and errors here.
            when {
                !r.ok -> history.add(entry(p.gameKey, p.name, SyncDirection.ERROR))
                r.direction == SyncDirection.UPLOAD || r.direction == SyncDirection.DOWNLOAD ->
                    history.add(entry(p.gameKey, p.name, r.direction))
                else -> {}
            }
        }

        val pending = pendingConflicts.count()
        val reachable = online && (attempted == 0 || reached)
        statusHolder.settle(enabled = settings.rommSaveSyncEnabled, online = reachable, pendingConflicts = pending, hadError = error)
        dev.cannoli.scorza.util.RommLog.write("=== sweep done: up=$up down=$down conflicts=$conflicts attempted=$attempted reachable=$reachable pending=$pending status=${statusHolder.state.value} error=$error ===")
        SyncSummary(up, down, conflicts)
    }

    private fun planFor(s: Scanned, op: SyncOperationDto?, batchFailed: Boolean, deviceId: String, onReach: (Boolean) -> Unit): SweepPlan {
        fun plan(action: SweepAction, downloadOp: SyncOperationDto? = null, conflict: PreLaunchOutcome.Conflict? = null) =
            SweepPlan(s.tag, s.base, s.gameKey, s.slot, s.emulator, s.romId, action, downloadOp, conflict)
        val local = s.local
        if (local == null) {
            if (s.anchor == null) return plan(SweepAction.NO_SAVE)
            val serverSaves = try {
                client.getSaves(s.romId, deviceId).also { onReach(true) }
            } catch (t: Throwable) {
                onReach(false)
                return plan(SweepAction.UNREACHABLE)
            }
            val save = serverSaves.firstOrNull { (it.slot ?: DEFAULT_SLOT) == s.slot } ?: return plan(SweepAction.NO_SAVE)
            return plan(SweepAction.REGENERATE, downloadOp = downloadOpFor(save, s.romId, s.slot))
        }
        if (batchFailed) return plan(SweepAction.UNREACHABLE)
        return when (op?.action) {
            "download" -> plan(SweepAction.DOWNLOAD, downloadOp = op)
            "upload" -> plan(SweepAction.UPLOAD)
            "conflict" -> {
                val cf = buildConflict(op, local, s.slot, s.romId, s.tag, s.base, s.gameKey, s.emulator)
                when (ConflictAutoResolver.classify(local.contentHash, s.anchor?.localContentHash, op.serverContentHash, s.anchor?.lastUploadedHash)) {
                    ConflictResolution.KEEP_LOCAL -> plan(SweepAction.KEEP_LOCAL, conflict = cf)
                    ConflictResolution.KEEP_SERVER -> plan(SweepAction.KEEP_SERVER, conflict = cf)
                    ConflictResolution.ESCALATE -> plan(SweepAction.ESCALATE, conflict = cf)
                }
            }
            else -> if (s.anchor?.localContentHash != local.contentHash) plan(SweepAction.UPLOAD) else plan(SweepAction.UP_TO_DATE)
        }
    }

    private suspend fun executePlan(p: SweepPlan, deviceId: String): ExecResult = when (p.action) {
        SweepAction.UPLOAD -> {
            statusHolder.setActive(SaveSyncStatus.UPLOADING)
            try {
                uploadActive(p.tag, p.name, p.gameKey, p.slot, p.romId, p.emulator, deviceId, overwrite = false)
                ExecResult(SyncDirection.UPLOAD, true, "uploaded")
            } catch (t: Throwable) {
                ExecResult(SyncDirection.UPLOAD, false, "upload failed (${errLabel(t)})")
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
                ExecResult(SyncDirection.UPLOAD, false, "keep-local failed (${errLabel(t)})")
            }
        }
        SweepAction.KEEP_SERVER -> {
            statusHolder.setActive(SaveSyncStatus.DOWNLOADING)
            try {
                applyConflictUseServer(p.conflict!!, deviceId)
                pendingConflicts.delete(p.gameKey)
                ExecResult(SyncDirection.DOWNLOAD, true, "kept server")
            } catch (t: Throwable) {
                ExecResult(SyncDirection.DOWNLOAD, false, "keep-server failed (${errLabel(t)})")
            }
        }
        SweepAction.ESCALATE -> {
            escalate(p.gameKey, p.name, p.conflict!!)
            ExecResult(SyncDirection.CONFLICT, true, "escalated for manual resolve")
        }
        else -> ExecResult(null, true, "skipped")
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

    private fun errLabel(t: Throwable): String =
        (t as? dev.cannoli.scorza.romm.RommException)?.statusCode?.toString() ?: (t.message ?: "error")

    suspend fun resolvePending(gameKey: String, keepLocal: Boolean, resolveGame: (String) -> Triple<String, String, String?>?): Boolean = withContext(Dispatchers.IO) {
        val pc = pendingConflicts.get(gameKey) ?: return@withContext false
        val deviceId = registrar.deviceId() ?: return@withContext false
        val (tag, base, emulator) = resolveGame(gameKey) ?: return@withContext false
        val slot = store.activeSlot(gameKey)
        val c = PreLaunchOutcome.Conflict(gameKey, slot, null, pc.serverUpdatedAt, null, pc.serverSaveId ?: 0, pc.romId, tag, base, emulator)
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
