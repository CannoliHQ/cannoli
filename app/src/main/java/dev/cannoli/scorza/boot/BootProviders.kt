package dev.cannoli.scorza.boot

import android.os.Handler
import android.os.Looper
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

@Module
@InstallIn(ActivityComponent::class)
object BootProviders {

    @Provides
    @ActivityScoped
    fun provideBootSequencer(
        permissionStatus: PermissionStatus,
        settings: SettingsRepository,
        setupCoordinator: SetupCoordinator,
        initializer: Lazy<BootInitializer>,
        startStorageDependentHolder: StartStorageDependentHolder,
        @IoScope ioScope: CoroutineScope,
    ): BootSequencer = BootSequencer(
        permissionStatus = permissionStatus,
        isSetupResolved = {
            when {
                settings.setupCompleted -> true
                CannoliPaths(settings.sdCardRoot).settingsJson.exists() -> {
                    settings.setupCompleted = true; true
                }
                else -> false
            }
        },
        detectVolumes = { setupCoordinator.detectStorageVolumes() + ("Custom" to "") },
        onSetupResolved = { root ->
            if (root != null) settings.sdCardRoot = root
            settings.setupCompleted = true
        },
        startStorageDependent = { startStorageDependentHolder.invoke() },
        initRunner = BootSequencer.InitRunner { onPhase -> initializer.get().run(onPhase) },
        scope = ioScope,
        mountWatcher = object : MountWatcher {
            private var watch: SetupCoordinator.StorageWatch? = null
            override fun start(onChange: () -> Unit) {
                if (watch == null) watch = setupCoordinator.watchStorage(onChange)
            }
            override fun stop() {
                watch?.stop()
                watch = null
            }
        },
        scheduleTimeout = { delayMs, action ->
            Handler(Looper.getMainLooper()).postDelayed(action, delayMs)
        },
    )
}

/**
 * Lets MainActivity register its `startStorageDependent()` (controller bridge, blacklist,
 * input log) so BootSequencer can invoke it on the NeedsPermission -> Initializing edge,
 * without BootSequencer depending on the Activity.
 */
@ActivityScoped
class StartStorageDependentHolder @Inject constructor() {
    private var action: () -> Unit = {}
    fun register(action: () -> Unit) { this.action = action }
    fun invoke() = action()
}
