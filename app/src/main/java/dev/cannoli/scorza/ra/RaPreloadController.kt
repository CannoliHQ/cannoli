package dev.cannoli.scorza.ra

import android.content.Context
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.BuildConfig
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class RaPreloadController @Inject constructor(
    private val nav: NavigationController,
    @IoScope private val ioScope: CoroutineScope,
    @ActivityContext private val context: Context,
    private val settings: SettingsRepository,
    private val romsRepository: RomsRepository,
) {
    fun preloadRom(rom: Rom, onComplete: () -> Unit = {}) {
        if (!isOnline()) {
            showResult(false, rom.displayName, context.getString(dev.cannoli.scorza.R.string.ra_preload_failed))
            return
        }
        nav.dialogState.value = DialogState.RAPreloadProgress(rom.displayName)
        ioScope.launch { finish(preloadOne(rom), rom.displayName, onComplete) }
    }

    fun start(
        romPath: String,
        platformTag: String,
        displayName: String,
        gameId: Int,
        onComplete: () -> Unit = {},
    ) {
        if (!isOnline()) {
            showResult(false, displayName, context.getString(dev.cannoli.scorza.R.string.ra_preload_failed))
            return
        }
        nav.dialogState.value = DialogState.RAPreloadProgress(displayName)
        ioScope.launch {
            finish(runPreload(client(), romPath, platformTag, gameId, null), displayName, onComplete)
        }
    }

    fun preloadBulk(roms: List<Rom>, onComplete: () -> Unit = {}) {
        if (roms.isEmpty()) {
            nav.dialogState.value = DialogState.None
            return
        }
        if (!isOnline()) {
            nav.dialogState.value = DialogState.RAPreloadResult(
                success = false,
                message = context.getString(dev.cannoli.scorza.R.string.ra_preload_failed),
            )
            return
        }
        ioScope.launch {
            var cached = 0
            for ((i, rom) in roms.withIndex()) {
                withContext(Dispatchers.Main) {
                    nav.dialogState.value = DialogState.RAPreloadProgress("${rom.displayName} (${i + 1}/${roms.size})")
                }
                if (preloadOne(rom) is RaOfflinePreloader.Result.Success) cached++
            }
            withContext(Dispatchers.Main) {
                onComplete()
                nav.dialogState.value = DialogState.RAPreloadResult(
                    success = cached > 0,
                    message = context.getString(dev.cannoli.scorza.R.string.ra_preload_bulk_done, cached, roms.size),
                )
            }
        }
    }

    private suspend fun preloadOne(rom: Rom): RaOfflinePreloader.Result {
        val client = client()
        var gameId = rom.raGameId ?: 0
        var hash: String? = null
        if (gameId <= 0) {
            val consoleId = RaConsoles.MAP[rom.platformTag.uppercase()]
            if (consoleId != null) {
                hash = RaHasher.hashRom(rom.path.absolutePath, consoleId)
                if (hash != null) {
                    val resolved = client.resolveGameId(settings.raUsername, settings.raToken, hash)
                    if (resolved < 0) return RaOfflinePreloader.Result.Failure("offline")
                    gameId = resolved
                }
            }
        }
        val result = runPreload(client, rom.path.absolutePath, rom.platformTag, gameId, hash)
        if (result is RaOfflinePreloader.Result.Success && gameId > 0) {
            romsRepository.setRaCachedGameId(rom.id, gameId)
        }
        return result
    }

    private suspend fun runPreload(
        client: RaConnectClient,
        romPath: String,
        platformTag: String,
        gameId: Int,
        hash: String?,
    ): RaOfflinePreloader.Result {
        if (gameId <= 0) return RaOfflinePreloader.Result.NoAchievements
        val store = RaOfflineStore(CannoliPaths(settings.sdCardRoot).configRaOffline)
        return RaOfflinePreloader(client, store).preload(
            romPath = romPath,
            platformTag = platformTag,
            gameId = gameId,
            username = settings.raUsername,
            token = settings.raToken,
            hash = hash,
        )
    }

    private suspend fun finish(result: RaOfflinePreloader.Result, displayName: String, onComplete: () -> Unit) {
        withContext(Dispatchers.Main) {
            onComplete()
            showResult(result !is RaOfflinePreloader.Result.Failure, displayName, messageFor(result))
        }
    }

    private fun showResult(success: Boolean, displayName: String, message: String) {
        nav.dialogState.value = DialogState.RAPreloadResult(success = success, message = "$displayName\n\n$message")
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun client() = RaConnectClient(userAgent = "Cannoli/${BuildConfig.VERSION_NAME}")

    private fun messageFor(result: RaOfflinePreloader.Result): String = when (result) {
        is RaOfflinePreloader.Result.Success -> context.resources.getQuantityString(
            dev.cannoli.scorza.R.plurals.ra_preload_success,
            result.achievementCount, result.achievementCount, result.totalPoints,
        )
        is RaOfflinePreloader.Result.NoAchievements ->
            context.getString(dev.cannoli.scorza.R.string.ra_preload_none)
        is RaOfflinePreloader.Result.Failure ->
            context.getString(dev.cannoli.scorza.R.string.ra_preload_failed)
    }
}
