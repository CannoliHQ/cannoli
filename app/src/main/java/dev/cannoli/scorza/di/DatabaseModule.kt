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
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.util.ArcadeTitleLookup
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.RomDirectoryWalker
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideCannoliDatabase(paths: CannoliPathsProvider): CannoliDatabase =
        CannoliDatabase(paths)

    @Provides @Singleton
    fun provideArtworkLookup(paths: CannoliPathsProvider): ArtworkLookup =
        ArtworkLookup(paths)

    @Provides @Singleton
    fun provideArcadeTitleLookup(paths: CannoliPathsProvider): ArcadeTitleLookup =
        ArcadeTitleLookup(paths)

    @Provides @Singleton
    fun provideRomDirectoryWalker(
        paths: CannoliPathsProvider,
        arcadeTitleLookup: ArcadeTitleLookup,
        @ApplicationContext context: Context,
    ): RomDirectoryWalker = RomDirectoryWalker(paths, context.assets, arcadeTitleLookup)

    @Provides @Singleton
    fun provideRomScanner(
        db: CannoliDatabase,
        walker: RomDirectoryWalker,
        artwork: ArtworkLookup,
    ): RomScanner = RomScanner(db, walker, artwork)

    @Provides @Singleton
    fun provideRomsRepository(
        paths: CannoliPathsProvider,
        db: CannoliDatabase,
        artwork: ArtworkLookup,
    ): RomsRepository = RomsRepository(paths, db, artwork)

    @Provides @Singleton
    fun provideAppsRepository(db: CannoliDatabase): AppsRepository =
        AppsRepository(db)

    @Provides @Singleton
    fun provideCollectionsRepository(db: CannoliDatabase): CollectionsRepository =
        CollectionsRepository(db)

    @Provides @Singleton
    fun provideRecentlyPlayedRepository(db: CannoliDatabase): RecentlyPlayedRepository =
        RecentlyPlayedRepository(db)

    @Provides @Singleton
    fun provideRommLinkRepository(
        db: CannoliDatabase,
        paths: CannoliPathsProvider,
    ): RommLinkRepository = RommLinkRepository(db) { paths.romDir }
}
