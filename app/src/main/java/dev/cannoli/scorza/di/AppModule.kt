package dev.cannoli.scorza.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
    fun provideAtomicRename(settings: SettingsRepository): AtomicRename =
        AtomicRename(File(settings.sdCardRoot))
}
