package dev.cannoli.scorza.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.CheevosOverrideMigration
import dev.cannoli.scorza.launcher.DelfinoLauncher
import dev.cannoli.scorza.launcher.GuidesKeyMigration
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.launcher.LaunchState
import dev.cannoli.scorza.launcher.RetroArchLauncher
import dev.cannoli.scorza.settings.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LaunchModule {

    @Provides @Singleton
    fun provideRetroArchLauncher(
        @ApplicationContext context: Context,
    ): RetroArchLauncher = RetroArchLauncher(context)

    @Provides @Singleton
    fun provideDelfinoLauncher(
        @ApplicationContext context: Context,
    ): DelfinoLauncher = DelfinoLauncher(context)

    @Provides @Singleton
    fun provideCheevosOverrideMigration(
        paths: CannoliPathsProvider,
    ): CheevosOverrideMigration = CheevosOverrideMigration(
        configRetroArchDir = { CannoliPaths(paths.root).configRetroArch },
    )

    @Provides @Singleton
    fun provideGuidesKeyMigration(
        paths: CannoliPathsProvider,
        romsRepository: RomsRepository,
    ): GuidesKeyMigration = GuidesKeyMigration(
        guidesDir = { CannoliPaths(paths.root).guidesDir },
        positionsFile = { CannoliPaths(paths.root).guidePositionsFile },
        roms = { romsRepository.allRoms() },
    )

    @Provides @Singleton
    fun provideLaunchManager(
        @ApplicationContext context: Context,
        settings: SettingsRepository,
        platformConfig: PlatformConfig,
        retroArchLauncher: RetroArchLauncher,
        apkLauncher: ApkLauncher,
        delfinoLauncher: DelfinoLauncher,
        launchState: LaunchState,
        activeMappingHolder: dev.cannoli.scorza.input.runtime.ActiveMappingHolder,
        portRouter: dev.cannoli.scorza.input.runtime.PortRouter,
        installedCoreService: InstalledCoreService,
        gameOverrides: dev.cannoli.scorza.db.GameOverrideStore,
    ): LaunchManager = LaunchManager(
        context, settings, platformConfig,
        retroArchLauncher, apkLauncher, delfinoLauncher, launchState, activeMappingHolder,
        portRouter, installedCoreService, gameOverrides
    )
}
