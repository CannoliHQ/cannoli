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
        ioScope.launch {
            val result = runCatching { engine().preloadOne(client(), rom) }
                .getOrDefault(RaOfflinePreloader.Result.Failure("error"))
            finish(result, rom.displayName, onComplete)
        }
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
            val result = runCatching { engine().refresh(client(), romPath, platformTag, gameId, null) }
                .getOrDefault(RaOfflinePreloader.Result.Failure("error"))
            finish(result, displayName, onComplete)
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
            val cached = runCatching {
                engine().preloadAll(client(), roms) { rom, i ->
                    withContext(Dispatchers.Main) {
                        if (!activityGone()) {
                            nav.dialogState.value =
                                DialogState.RAPreloadProgress("${rom.displayName} (${i + 1}/${roms.size})")
                        }
                    }
                }
            }.getOrDefault(0)
            withContext(Dispatchers.Main) {
                onComplete()
                if (!activityGone()) {
                    nav.dialogState.value = DialogState.RAPreloadResult(
                        success = cached > 0,
                        message = context.getString(dev.cannoli.scorza.R.string.ra_preload_bulk_done, cached, roms.size),
                    )
                }
            }
        }
    }

    private fun engine() = RaPreloadEngine(
        store = RaOfflineStore(CannoliPaths(settings.sdCardRoot).configRaOffline),
        romsRepository = romsRepository,
        username = settings.raUsername,
        token = settings.raToken,
    )

    private suspend fun finish(result: RaOfflinePreloader.Result, displayName: String, onComplete: () -> Unit) {
        withContext(Dispatchers.Main) {
            onComplete()
            if (!activityGone()) {
                showResult(result !is RaOfflinePreloader.Result.Failure, displayName, messageFor(result))
            }
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

    /** The preload coroutines run on the process-lifetime IoScope so they finish even if the user
     *  navigates away; this skips the UI transitions once the hosting Activity is gone. */
    private fun activityGone(): Boolean {
        val act = context as? android.app.Activity ?: return false
        return act.isFinishing || act.isDestroyed
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
