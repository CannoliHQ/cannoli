package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.PlatformMap
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.toDomain
import dev.cannoli.scorza.util.RommLog
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

    /**
     * The last completed sync failed and none has succeeded since, so the mirror may be out of
     * date. Deliberately untouched when a sync starts: [status] flips to SYNCING on every run, so
     * a retry would otherwise clear this and restore it moments later on a fast failure.
     */
    private val _stale = MutableStateFlow(false)
    val stale: StateFlow<Boolean> = _stale

    private val mutex = Mutex()

    suspend fun syncFull() = run(full = true)
    suspend fun syncDelta() = run(full = false)

    private suspend fun run(full: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _status.value = SyncStatus.SYNCING
            _progress.value = SyncProgress(0, 0)
            val startedAt = System.currentTimeMillis()
            try {
                if (full) db.clearAll()
                val cursor = if (full) null else db.getSyncState(KEY_CURSOR)
                val ingested = mutableListOf<String?>()
                RommLog.write("cache sync start full=$full cursor=$cursor")

                // Always pull the whole platform list, even on a delta. RomM does not bump a
                // platform's updated_at when a rom under it changes, so an updated_after-filtered
                // list omits the platforms owning the changed roms: the sweep below would then
                // discard every one of them, and the rom_count reconcile would have nothing to
                // compare against.
                val platformDtos = client.getPlatforms()
                val supported = platformMap.toDomain(platformDtos)
                val supportedIds = supported.map { it.id }.toSet()
                val updatedById = platformDtos.associate { it.id to it.updatedAt }
                val rows = supported.map { it to updatedById[it.id] }
                if (full) db.replacePlatforms(rows) else db.upsertPlatforms(rows)

                var pulled = 0
                var stored = 0
                var repulled = 0
                if (full) {
                    supported.forEachIndexed { index, platform ->
                        _progress.value = SyncProgress(index, supported.size, platform.displayName)
                        pulled += repullPlatform(platform.id, ingested, reconcile = false)
                    }
                    stored = pulled
                    _progress.value = SyncProgress(supported.size, supported.size)
                } else {
                    val sweep = deltaSweep(cursor, supportedIds, ingested)
                    pulled = sweep.first
                    stored = sweep.second
                    val serverCounts = supported.associate { it.id to it.romCount }
                    val cachedCounts = db.gameCountsByPlatform()
                    RommSyncPlanner.platformsNeedingReconcile(cachedCounts, serverCounts).forEach {
                        stored += repullPlatform(it, ingested, reconcile = true)
                        repulled++
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
                }.onFailure { RommLog.write("romm purge deleted platforms failed: ${it.message}") }
                runCatching {
                    val validRomIds = client.getRomIdentifiers().toSet()
                    val stale = db.allGameIds() - validRomIds
                    if (stale.isNotEmpty()) db.deleteGames(stale)
                }.onFailure { RommLog.write("romm purge deleted games failed: ${it.message}") }
                runCatching {
                    val groups = enabledGroups()
                    val seen = mutableSetOf<String>()
                    for (group in groups) {
                        val fetched = client.getCollections(group)
                        db.upsertCollections(fetched.map { RommCollection(it.id, it.group, it.name, it.romCount, it.virtualType) to null })
                        fetched.forEach { db.setCollectionMembers(it.id, it.romIds); seen.add(it.id) }
                    }
                    val stale = db.allCollectionIds() - seen
                    if (stale.isNotEmpty()) db.deleteCollections(stale)
                }.onFailure { RommLog.write("romm collection sync failed: ${it.message}") }

                // Only rom timestamps feed the cursor: it filters roms, and platform rows can be
                // stamped later than any rom, which would skip the gap between them for good.
                val next = RommSyncPlanner.nextCursor(cursor, ingested)
                next?.let { db.setSyncState(KEY_CURSOR, it) }
                _status.value = SyncStatus.IDLE
                _stale.value = false
                RommLog.write(
                    "cache sync done full=$full platforms=${supported.size} pulled=$pulled stored=$stored " +
                        "repulled=$repulled cursor=$next ${System.currentTimeMillis() - startedAt}ms"
                )
            } catch (t: Throwable) {
                RommLog.write("ERROR romm cache sync failed: ${t.message}")
                _status.value = SyncStatus.ERROR
                _stale.value = true
            }
        }
    }

    /** Returns the roms the server returned paired with the subset actually cached. */
    private fun deltaSweep(cursor: String?, supportedIds: Set<Int>, ingested: MutableList<String?>): Pair<Int, Int> {
        var pulled = 0
        var stored = 0
        var offset = 0
        while (true) {
            val page = client.getRoms(platformId = null, limit = PAGE, offset = offset, search = null, updatedAfter = cursor)
            val supportedItems = page.items.filter { it.platformId in supportedIds }
            db.upsertGames(supportedItems.map { GameRecord(it.toDomain(), it.updatedAt) })
            page.items.forEach { ingested.add(it.updatedAt) }
            pulled += page.items.size
            stored += supportedItems.size
            offset += page.items.size
            if (page.items.isEmpty() || offset >= page.total) break
        }
        return pulled to stored
    }

    private fun repullPlatform(platformId: Int, ingested: MutableList<String?>, reconcile: Boolean): Int {
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
        return seen.size
    }

    private companion object {
        const val KEY_CURSOR = "cursor"
        const val PAGE = RommLibrary.PAGE_SIZE
    }
}
