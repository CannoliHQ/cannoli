package dev.cannoli.scorza.ra

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
        var hash: String? = null
        if (gameId <= 0) {
            val consoleId = RaConsoles.MAP[rom.platformTag.uppercase()]
            if (consoleId != null) {
                hash = hasher(rom.path.absolutePath, consoleId)
                if (hash != null) {
                    val resolved = client.resolveGameId(username, token, hash)
                    if (resolved < 0) return RaOfflinePreloader.Result.Failure("offline")
                    gameId = resolved
                }
            }
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
        if (gameId <= 0) return RaOfflinePreloader.Result.NoAchievements
        return RaOfflinePreloader(client, store).preload(romPath, platformTag, gameId, username, token, hash)
    }

    suspend fun preloadAll(
        client: RaConnectClient,
        roms: List<Rom>,
        onProgress: suspend (rom: Rom, index: Int) -> Unit = { _, _ -> },
    ): Int {
        var cached = 0
        roms.forEachIndexed { index, rom ->
            onProgress(rom, index)
            val ok = try {
                preloadOne(client, rom) is RaOfflinePreloader.Result.Success
            } catch (_: Exception) {
                false
            }
            if (ok) cached++
        }
        return cached
    }
}
