package dev.cannoli.scorza.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.RecentlyPlayedRepository
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.ArcadeTitleLookup
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.RomDirectoryWalker
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton @CannoliRoot
    fun provideCannoliRoot(settings: SettingsRepository): File =
        File(settings.sdCardRoot)

    @Provides @Singleton @RomDir
    fun provideRomDir(
        @CannoliRoot root: File,
        settings: SettingsRepository,
    ): File {
        val customPath = settings.romDirectory.takeIf { it.isNotEmpty() }
        return customPath?.let { File(it) } ?: File(root, "Roms")
    }

    @Provides @Singleton
    fun provideCannoliDatabase(@CannoliRoot cannoliRoot: File): CannoliDatabase =
        CannoliDatabase(cannoliRoot)

    @Provides @Singleton
    fun provideArtworkLookup(@CannoliRoot cannoliRoot: File): ArtworkLookup =
        ArtworkLookup(cannoliRoot)

    @Provides @Singleton
    fun provideArcadeTitleLookup(@CannoliRoot cannoliRoot: File): ArcadeTitleLookup =
        ArcadeTitleLookup(cannoliRoot)

    @Provides @Singleton
    fun provideRomDirectoryWalker(
        @CannoliRoot cannoliRoot: File,
        @RomDir romDirectory: File,
        arcadeTitleLookup: ArcadeTitleLookup,
        @ApplicationContext context: Context,
    ): RomDirectoryWalker {
        val walker = RomDirectoryWalker(cannoliRoot, romDirectory, arcadeTitleLookup)
        walker.loadIgnoreLists(context.assets)
        return walker
    }

    @Provides @Singleton
    fun provideRomScanner(
        db: CannoliDatabase,
        walker: RomDirectoryWalker,
        artwork: ArtworkLookup,
    ): RomScanner = RomScanner(db, walker, artwork)

    @Provides @Singleton
    fun provideRomsRepository(
        @RomDir romDirectory: File,
        db: CannoliDatabase,
        artwork: ArtworkLookup,
    ): RomsRepository = RomsRepository(romDirectory, db, artwork)

    @Provides @Singleton
    fun provideAppsRepository(db: CannoliDatabase): AppsRepository =
        AppsRepository(db)

    @Provides @Singleton
    fun provideCollectionsRepository(db: CannoliDatabase): CollectionsRepository =
        CollectionsRepository(db)

    @Provides @Singleton
    fun provideRecentlyPlayedRepository(db: CannoliDatabase): RecentlyPlayedRepository =
        RecentlyPlayedRepository(db)
}
