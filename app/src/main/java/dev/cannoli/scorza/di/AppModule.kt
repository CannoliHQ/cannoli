package dev.cannoli.scorza.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.input.ProfileManager
import dev.cannoli.scorza.input.autoconfig.AssetCfgSource
import dev.cannoli.scorza.input.autoconfig.AutoconfigLoader
import dev.cannoli.scorza.input.autoconfig.AutoconfigMatcher
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.AtomicRename
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideGlobalOverridesManager(settings: SettingsRepository): GlobalOverridesManager =
        GlobalOverridesManager { settings.sdCardRoot }

    @Provides @Singleton
    fun provideProfileManager(settings: SettingsRepository): ProfileManager =
        ProfileManager(settings.sdCardRoot)

    @Provides @Singleton
    fun provideAutoconfigLoader(@ApplicationContext context: Context): AutoconfigLoader =
        AutoconfigLoader(AssetCfgSource(context))

    @Provides @Singleton
    fun provideAutoconfigMatcher(loader: AutoconfigLoader): AutoconfigMatcher =
        AutoconfigMatcher(loader.entries())

    @Provides @Singleton
    fun provideAtomicRename(settings: SettingsRepository): AtomicRename =
        AtomicRename(File(settings.sdCardRoot))
}
