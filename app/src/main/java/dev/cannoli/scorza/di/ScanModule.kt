package dev.cannoli.scorza.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.config.CoreInfoRepository
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.settings.SettingsRepository
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScanModule {

    @Provides @Singleton
    fun provideCoreInfoRepository(@ApplicationContext context: Context): CoreInfoRepository {
        val repo = CoreInfoRepository(
            context.assets,
            context.filesDir,
            File(context.applicationInfo.sourceDir).lastModified()
        )
        repo.load()
        return repo
    }

    @Provides @Singleton
    fun providePlatformConfig(
        settings: SettingsRepository,
        @ApplicationContext context: Context,
        coreInfo: CoreInfoRepository,
    ): PlatformConfig {
        val root = File(settings.sdCardRoot)
        val bundledCoresDir = LaunchManager.extractBundledCores(context)
        val config = PlatformConfig(root, context.assets, coreInfo, bundledCoresDir)
        config.load()
        return config
    }
}
