package dev.cannoli.scorza.achievements

import dev.cannoli.core.achievements.RaOfflineStore
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.Rom

/**
 * Android-free orchestration for offline preload: resolve the game id, fetch and store the set, and
 * mark the rom as cached. Extracted from RaPreloadController so the result handling, the
 * setRaCachedGameId side-effect, and bulk counting are unit-testable without a Context. The hasher
 * is injectable so tests can avoid the native rcheevos binding.
 */
class RaPreloadEngine(
    private val store: RaOfflineStore,
    private val romsRepository: RomsRepository,
    private val username: String,
    private val token: String,
    private val hasher: (String, Int) -> String? = { path, consoleId -> RaHasher.hashRom(path, consoleId) },
) {
    suspend fun preloadOne(client: RaConnectClient, rom: Rom): RaOfflinePreloader.Result {
        var gameId = rom.raGameId ?: 0
        // Hashed even when the id is already known: the offline request the game process makes is
        // hash-only, so a game cached without its hash file can never be matched back to this entry.
        val consoleId = RaConsoles.MAP[rom.platformTag.uppercase()]
        // Guarded as well as RaHasher guarding itself: an id we cannot compute is an
        // unidentified game, and must never surface as a failure that blames the network.
        val hash = consoleId?.let { runCatching { hasher(rom.path.absolutePath, it) }.getOrNull() }
        if (gameId <= 0 && hash != null) {
            val resolved = client.resolveGameId(username, token, hash)
            if (resolved < 0) return RaOfflinePreloader.Result.Failure("offline")
            gameId = resolved
        }
        val result = refresh(client, rom.path.absolutePath, rom.platformTag, gameId, hash)
        if (result is RaOfflinePreloader.Result.Success && gameId > 0) {
            romsRepository.setRaCachedGameId(rom.id, gameId)
        }
        return result
    }

    suspend fun refresh(
        client: RaConnectClient,
        romPath: String,
        platformTag: String,
        gameId: Int,
        hash: String?,
    ): RaOfflinePreloader.Result {
        // No id from the user and none from a hash. Reporting no achievements blamed the game,
        // and the generic failure blamed the network; neither was ever asked.
        if (gameId <= 0) return RaOfflinePreloader.Result.Unidentified
        return RaOfflinePreloader(client, store).preload(romPath, platformTag, gameId, username, token, hash)
    }

    /**
     * Re-fetches a game the cache already holds, under the platform and path it was cached with.
     *
     * Those two are read back rather than passed in because they are what the offline browser groups
     * and relaunches by: refreshing with blanks rewrites the source file as a blank line, which the
     * store reads as corrupt and drops, so a refresh would delete the entry it was meant to update.
     * A game the store does not know is left alone rather than rewritten from nothing.
     */
    suspend fun refreshCached(client: RaConnectClient, gameId: Int): RaOfflinePreloader.Result? {
        val entry = store.entry(gameId) ?: return null
        return refresh(client, entry.romPath, entry.platformTag, gameId, null)
    }

    data class BulkResult(val cached: Int)

    suspend fun preloadAll(
        client: RaConnectClient,
        roms: List<Rom>,
        onProgress: suspend (rom: Rom, index: Int) -> Unit = { _, _ -> },
    ): BulkResult {
        var cached = 0
        roms.forEachIndexed { index, rom ->
            onProgress(rom, index)
            val result = try {
                preloadOne(client, rom)
            } catch (_: Exception) {
                RaOfflinePreloader.Result.Failure("error")
            }
            if (result is RaOfflinePreloader.Result.Success) cached++
        }
        return BulkResult(cached)
    }
}
