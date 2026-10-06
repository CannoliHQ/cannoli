package dev.cannoli.scorza.achievements

import dev.cannoli.core.achievements.RaOfflineStore
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.Rom

class RaPreloadEngine(
    private val store: RaOfflineStore,
    private val romsRepository: RomsRepository,
    private val username: String,
    private val token: String,
    private val hasher: (String, Int) -> String? = { path, consoleId -> RaHasher.hashRom(path, consoleId) },
) {
    suspend fun preloadOne(client: RaConnectClient, rom: Rom): RaOfflinePreloader.Result {
        var gameId = rom.raGameId ?: 0
        val consoleId = RaConsoles.MAP[rom.platformTag.uppercase()]
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
        if (gameId <= 0) return RaOfflinePreloader.Result.Unidentified
        return RaOfflinePreloader(client, store).preload(romPath, platformTag, gameId, username, token, hash)
    }

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
