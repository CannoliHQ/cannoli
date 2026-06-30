package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.PlatformMap
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.toDomain
import dev.cannoli.scorza.util.ScanLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RommSyncCoordinator(
    private val client: RommClient,
    private val platformMap: PlatformMap,
    private val db: RommDatabase,
    private val enabledGroups: () -> Set<RommCollectionGroup> = { setOf(RommCollectionGroup.USER) },
    private val collectionsLabel: () -> String = { "Collections" },
) {
    enum class SyncStatus { IDLE, SYNCING, ERROR }

    data class SyncProgress(val completed: Int, val total: Int, val platform: String? = null)

    private val _status = MutableStateFlow(SyncStatus.IDLE)
    val status: StateFlow<SyncStatus> = _status

    private val _progress = MutableStateFlow(SyncProgress(0, 0))
    val progress: StateFlow<SyncProgress> = _progress

    private val mutex = Mutex()

    suspend fun syncFull() = run(full = true)
    suspend fun syncDelta() = run(full = false)

    private suspend fun run(full: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _status.value = SyncStatus.SYNCING
            _progress.value = SyncProgress(0, 0)
            try {
                if (full) db.clearAll()
                val cursor = if (full) null else db.getSyncState(KEY_CURSOR)
                val ingested = mutableListOf<String?>()

                val platformDtos = client.getPlatforms(updatedAfter = cursor)
                val supported = platformMap.toDomain(platformDtos)
                val supportedIds = supported.map { it.id }.toSet()
                val updatedById = platformDtos.associate { it.id to it.updatedAt }
                val rows = supported.map { it to updatedById[it.id] }
                if (full) db.replacePlatforms(rows) else db.upsertPlatforms(rows)
                platformDtos.forEach { ingested.add(it.updatedAt) }

                if (full) {
                    supported.forEachIndexed { index, platform ->
                        _progress.value = SyncProgress(index, supported.size, platform.displayName)
                        repullPlatform(platform.id, ingested, reconcile = false)
                    }
                    _progress.value = SyncProgress(supported.size, supported.size)
                } else {
                    deltaSweep(cursor, supportedIds, ingested)
                    val serverCounts = supported.associate { it.id to it.romCount }
                    val cachedCounts = db.gameCountsByPlatform()
                    RommSyncPlanner.platformsNeedingReconcile(cachedCounts, serverCounts).forEach {
                        repullPlatform(it, ingested, reconcile = true)
                    }
                }

                // Everything past the platform pull is the "finishing up" phase (deletion
                // reconcile + collection sync); label it so the screen never blanks out.
                _progress.value = _progress.value.copy(platform = collectionsLabel())

                // Reconcile server-side deletions: drop any cached platform/game whose id is no
                // longer on the server. Deltas only carry updated rows, never deletes, so without
                // this a deleted platform or game lingers in the cache.
                runCatching {
                    val validPlatformIds = client.getPlatformIdentifiers().toSet()
                    val stale = db.allPlatformIds() - validPlatformIds
                    if (stale.isNotEmpty()) db.deletePlatforms(stale)
                }.onFailure { ScanLog.write("romm purge deleted platforms failed: ${it.message}") }
                runCatching {
                    val validRomIds = client.getRomIdentifiers().toSet()
                    val stale = db.allGameIds() - validRomIds
                    if (stale.isNotEmpty()) db.deleteGames(stale)
                }.onFailure { ScanLog.write("romm purge deleted games failed: ${it.message}") }
                runCatching {
                    val groups = enabledGroups()
                    val seen = mutableSetOf<String>()
                    for (group in groups) {
                        val fetched = client.getCollections(group)
                        db.upsertCollections(fetched.map { RommCollection(it.id, it.group, it.name, it.romCount) to null })
                        fetched.forEach { db.setCollectionMembers(it.id, it.romIds); seen.add(it.id) }
                    }
                    val stale = db.allCollectionIds() - seen
                    if (stale.isNotEmpty()) db.deleteCollections(stale)
                }.onFailure { ScanLog.write("romm collection sync failed: ${it.message}") }

                RommSyncPlanner.nextCursor(cursor, ingested)?.let { db.setSyncState(KEY_CURSOR, it) }
                _status.value = SyncStatus.IDLE
            } catch (t: Throwable) {
                ScanLog.write("ERROR romm sync failed: ${t.message}")
                _status.value = SyncStatus.ERROR
            }
        }
    }

    private fun deltaSweep(cursor: String?, supportedIds: Set<Int>, ingested: MutableList<String?>) {
        var offset = 0
        while (true) {
            val page = client.getRoms(platformId = null, limit = PAGE, offset = offset, search = null, updatedAfter = cursor)
            db.upsertGames(page.items.filter { it.platformId in supportedIds }.map { GameRecord(it.toDomain(), it.updatedAt) })
            page.items.forEach { ingested.add(it.updatedAt) }
            offset += page.items.size
            if (page.items.isEmpty() || offset >= page.total) break
        }
    }

    private fun repullPlatform(platformId: Int, ingested: MutableList<String?>, reconcile: Boolean) {
        val seen = mutableSetOf<Int>()
        var offset = 0
        while (true) {
            val page = client.getRoms(platformId = platformId, limit = PAGE, offset = offset, search = null, updatedAfter = null)
            db.upsertGames(page.items.map { GameRecord(it.toDomain(), it.updatedAt) })
            page.items.forEach { seen.add(it.id); ingested.add(it.updatedAt) }
            offset += page.items.size
            if (page.items.isEmpty() || offset >= page.total) break
        }
        if (reconcile) {
            val stale = RommSyncPlanner.staleIds(db.cachedGameIds(platformId), seen)
            if (stale.isNotEmpty()) db.deleteGames(stale)
        }
    }

    private companion object {
        const val KEY_CURSOR = "cursor"
        const val PAGE = RommLibrary.PAGE_SIZE
    }
}
