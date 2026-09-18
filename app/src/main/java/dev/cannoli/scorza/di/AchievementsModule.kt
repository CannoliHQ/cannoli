package dev.cannoli.scorza.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cannoli.core.achievements.RaOfflineStore
import dev.cannoli.core.achievements.RaPendingUnlocks
import dev.cannoli.scorza.achievements.RaConnectClient
import dev.cannoli.scorza.achievements.RaPendingDrainer
import dev.cannoli.scorza.achievements.RaPreloadEngine
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.ErrorLog
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AchievementsModule {

    @Provides @Singleton
    fun provideRaConnectClient(): RaConnectClient = RaConnectClient(log = ErrorLog::write)

    @Provides @Singleton
    fun provideRaPendingDrainer(
        paths: CannoliPathsProvider,
        romsRepository: RomsRepository,
        settings: SettingsRepository,
        client: RaConnectClient,
    ): RaPendingDrainer {
        val root = CannoliPaths(paths.root).configRetroAchievements
        return RaPendingDrainer(
            pending = RaPendingUnlocks(File(root, "Pending")),
            client = client,
            // Built fresh per call rather than injected as a singleton, so a re-login mid-session
            // is not stuck refreshing under the account that was current when this was first
            // resolved. Mirrors RaPreloadController.engine(), which reads settings the same way.
            refreshGame = { gameId ->
                RaPreloadEngine(
                    store = RaOfflineStore(CannoliPaths(paths.root).configRaOffline),
                    romsRepository = romsRepository,
                    username = settings.raUsername,
                    token = settings.raToken,
                ).refreshCached(client, gameId)
            },
        )
    }
}
