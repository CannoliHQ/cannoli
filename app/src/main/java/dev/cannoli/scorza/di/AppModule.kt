package dev.cannoli.scorza.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.AtomicRename
import dev.cannoli.scorza.util.RomDirectoryWalker
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideGlobalOverridesManager(settings: SettingsRepository): GlobalOverridesManager =
        GlobalOverridesManager { settings.sdCardRoot }

    @Provides @Singleton
    fun provideAtomicRename(paths: CannoliPathsProvider, walker: RomDirectoryWalker, artwork: ArtworkLookup): AtomicRename =
        AtomicRename({ paths.root }, walker, artwork)
}
