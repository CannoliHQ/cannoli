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
        paths: CannoliPathsProvider,
        @ApplicationContext context: Context,
        coreInfo: CoreInfoRepository,
        settings: dev.cannoli.scorza.settings.SettingsRepository,
    ): PlatformConfig {
        val bundledCoresDir = LaunchManager.extractBundledCores(context)
        return PlatformConfig(
            { paths.root }, context.assets, coreInfo, bundledCoresDir,
            context.getString(dev.cannoli.scorza.R.string.value_empty_override),
            context.getString(dev.cannoli.scorza.R.string.value_needs_setup),
            context.getString(dev.cannoli.scorza.R.string.value_emulator_source_internal),
            context.getString(dev.cannoli.scorza.R.string.value_emulator_source_standalone),
            // Read lazily: settings are not loaded at construction, and this is only consulted
            // while migrating a mapping written before the source split.
            legacyExternalRaPackage = {
                settings.retroArchPackage.takeIf { it.isNotEmpty() && it != context.packageName }
            },
        )
    }
}
