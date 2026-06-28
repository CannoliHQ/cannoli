package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.RommClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SlotInfo(
    val slot: String,
    val isActive: Boolean,
    val serverUpdatedAt: String?,
    val saveId: Int?,
)

class SlotManager(
    private val client: RommClient,
    private val store: SaveSyncStore,
    private val resolver: LocalSaveResolver,
    private val registrar: DeviceRegistrar,
    private val pathsProvider: CannoliPathsProvider,
    private val service: SaveSyncService,
) {
    private val paths: CannoliPaths = CannoliPaths(pathsProvider.root)

    suspend fun listSlots(gameKey: String, romId: Int): List<SlotInfo> = withContext(Dispatchers.IO) {
        val active = store.activeSlot(gameKey)
        val deviceId = registrar.deviceId()
        val server = if (deviceId != null) runCatching { client.getSaves(romId, deviceId) }.getOrDefault(emptyList()) else emptyList()
        val slots = LinkedHashMap<String, SlotInfo>()
        slots[DEFAULT_SLOT] = SlotInfo(DEFAULT_SLOT, active == DEFAULT_SLOT, null, null)
        for (s in server) {
            val name = s.slot ?: continue
            slots[name] = SlotInfo(name, name == active, s.updatedAt, s.id)
        }
        for (row in store.listSlots(gameKey)) {
            if (!slots.containsKey(row.slot)) {
                slots[row.slot] = SlotInfo(row.slot, row.slot == active, row.serverUpdatedAt, row.rommSaveId)
            }
        }
        slots.values.toList()
    }

    suspend fun create(
        gameKey: String,
        tag: String,
        base: String,
        romId: Int,
        emulator: String?,
        name: String,
    ) = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name != DEFAULT_SLOT)
        val deviceId = registrar.deviceId() ?: return@withContext
        service.uploadActive(tag, base, gameKey, name, romId, emulator, deviceId, overwrite = false)
        store.setActiveSlot(gameKey, name)
    }

    suspend fun switch(
        gameKey: String,
        tag: String,
        base: String,
        romId: Int,
        emulator: String?,
        target: String,
    ) = withContext(Dispatchers.IO) {
        val deviceId = registrar.deviceId() ?: return@withContext
        val current = store.activeSlot(gameKey)
        if (current == target) return@withContext
        val local = resolver.resolve(tag, base)
        val anchor = store.get(gameKey, current)
        if (local != null && anchor?.localContentHash != local.contentHash) {
            runCatching { service.uploadActive(tag, base, gameKey, current, romId, emulator, deviceId, overwrite = false) }
        }
        val serverLatest = runCatching { client.getSaves(romId, deviceId) }.getOrDefault(emptyList())
            .filter { (it.slot ?: DEFAULT_SLOT) == target }
            .maxByOrNull { it.updatedAt }
        if (serverLatest != null) {
            val tmp = File.createTempFile("romm-slot", ".bin", paths.configCache.apply { mkdirs() })
            try {
                client.downloadSaveContent(serverLatest.id, deviceId, tmp)
                service.backupBeforeDownload(tag, base)
                resolver.applyDownload(tag, base, tmp)
                val confirmed = runCatching { client.confirmSaveDownloaded(serverLatest.id, deviceId) }.getOrNull()
                val hash = resolver.resolve(tag, base)?.contentHash
                store.upsert(
                    SaveSyncRow(
                        gameKey = gameKey,
                        slot = target,
                        rommRomId = romId,
                        rommSaveId = serverLatest.id,
                        lastSyncedAt = serverLatest.updatedAt,
                        lastUploadedHash = confirmed?.contentHash ?: serverLatest.contentHash ?: hash,
                        localContentHash = hash,
                        serverUpdatedAt = serverLatest.updatedAt,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } finally {
                tmp.delete()
            }
        }
        store.setActiveSlot(gameKey, target)
    }

    suspend fun delete(gameKey: String, romId: Int, slot: String) = withContext(Dispatchers.IO) {
        require(slot != DEFAULT_SLOT) { "autosave cannot be deleted" }
        val deviceId = registrar.deviceId()
        val serverIds = if (deviceId != null) {
            runCatching { client.getSaves(romId, deviceId) }.getOrDefault(emptyList())
                .filter { (it.slot ?: DEFAULT_SLOT) == slot }
                .map { it.id }
        } else emptyList()
        val toDelete = serverIds.ifEmpty { listOfNotNull(store.get(gameKey, slot)?.rommSaveId) }
        if (toDelete.isNotEmpty()) runCatching { client.deleteSaves(toDelete) }
        store.delete(gameKey, slot)
        if (store.activeSlot(gameKey) == slot) store.setActiveSlot(gameKey, DEFAULT_SLOT)
    }

    suspend fun rename(
        gameKey: String,
        tag: String,
        base: String,
        romId: Int,
        emulator: String?,
        oldSlot: String,
        newSlot: String,
    ) = withContext(Dispatchers.IO) {
        require(oldSlot != DEFAULT_SLOT) { "autosave cannot be renamed" }
        require(newSlot.isNotBlank() && newSlot != DEFAULT_SLOT)
        val deviceId = registrar.deviceId() ?: return@withContext
        val isActive = store.activeSlot(gameKey) == oldSlot
        if (isActive) {
            service.uploadActive(tag, base, gameKey, newSlot, romId, emulator, deviceId, overwrite = false)
        } else {
            val latest = runCatching { client.getSaves(romId, deviceId) }.getOrDefault(emptyList())
                .filter { (it.slot ?: DEFAULT_SLOT) == oldSlot }
                .maxByOrNull { it.updatedAt }
            if (latest != null) {
                val tmp = File.createTempFile("romm-ren", ".bin", paths.configCache.apply { mkdirs() })
                try {
                    client.downloadSaveContent(latest.id, deviceId, tmp)
                    val saved = client.uploadSave(romId, emulator, newSlot, deviceId, false, tmp)
                    store.upsert(
                        SaveSyncRow(
                            gameKey = gameKey,
                            slot = newSlot,
                            rommRomId = romId,
                            rommSaveId = saved.id,
                            lastSyncedAt = saved.updatedAt,
                            lastUploadedHash = saved.contentHash,
                            localContentHash = null,
                            serverUpdatedAt = saved.updatedAt,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                } finally {
                    tmp.delete()
                }
            }
        }
        val oldId = store.get(gameKey, oldSlot)?.rommSaveId
        if (oldId != null) runCatching { client.deleteSaves(listOf(oldId)) }
        store.delete(gameKey, oldSlot)
        if (isActive) store.setActiveSlot(gameKey, newSlot)
    }

}
