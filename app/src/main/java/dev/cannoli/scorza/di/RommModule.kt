package dev.cannoli.scorza.di

import android.content.Context
import coil.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.romm.PlatformMap
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.romm.RommHttp
import dev.cannoli.scorza.romm.RommImageLoader
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommSlugMap
import dev.cannoli.scorza.romm.cache.CachedRommLibrary
import dev.cannoli.scorza.romm.cache.RommDatabase
import dev.cannoli.scorza.romm.cache.RommSyncCoordinator
import dev.cannoli.scorza.romm.download.RommDownloadQueue
import dev.cannoli.scorza.romm.download.RommDownloader
import dev.cannoli.scorza.romm.download.RommInstaller
import dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel
import dev.cannoli.scorza.util.ArtworkLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RommModule {

    @Provides @Singleton
    fun provideRommHttp(store: RommConnectionStore): RommHttp =
        RommHttp(
            tokenProvider = { store.token },
            allowSelfSignedProvider = { store.allowSelfSigned },
        )

    @Provides @Singleton
    fun provideRommClient(store: RommConnectionStore, http: RommHttp): RommClient =
        RommClient(baseUrlProvider = { store.host }, clientProvider = { http.client() })

    @Provides @Singleton
    fun provideRommSlugMap(@ApplicationContext context: Context): RommSlugMap =
        RommSlugMap.load(context.assets)

    @Provides @Singleton
    fun providePlatformMap(slugMap: RommSlugMap, platformConfig: PlatformConfig): PlatformMap =
        PlatformMap(slugMap, isSupported = { platformConfig.isKnownTag(it) })

    @Provides @Singleton
    fun provideRommDatabase(paths: CannoliPathsProvider): RommDatabase =
        RommDatabase { CannoliPaths(paths.root).rommDatabase }

    @Provides @Singleton
    fun provideRommSyncCoordinator(
        client: RommClient,
        platformMap: PlatformMap,
        db: RommDatabase,
    ): RommSyncCoordinator = RommSyncCoordinator(client, platformMap, db)

    @Provides @Singleton
    fun provideRommLibrary(db: RommDatabase): RommLibrary =
        CachedRommLibrary(db)

    @Provides @Singleton
    fun provideRommBrowseViewModel(
        library: RommLibrary,
        syncCoordinator: RommSyncCoordinator,
        db: RommDatabase,
        romsRepository: dev.cannoli.scorza.db.RomsRepository,
        linkRepository: RommLinkRepository,
        settings: dev.cannoli.scorza.settings.SettingsRepository,
    ): RommBrowseViewModel =
        RommBrowseViewModel(
            library = library,
            syncCoordinator = syncCoordinator,
            db = db,
            presentNamesFor = { tag -> romsRepository.presentFileNames(tag) },
            linkedIdsProvider = { linkRepository.presentRommIds() },
            hiddenTagsProvider = { settings.hiddenRommPlatforms },
        )

    @Provides @Singleton
    fun provideRommImageLoader(
        @ApplicationContext context: Context,
        http: RommHttp,
        paths: CannoliPathsProvider,
    ): ImageLoader =
        RommImageLoader.build(context, http, java.io.File(dev.cannoli.scorza.config.CannoliPaths(paths.root).configCache, "RommArt"))

    @Provides @Singleton
    fun provideRommArtDownloader(
        http: RommHttp,
        paths: CannoliPathsProvider,
    ): dev.cannoli.scorza.romm.art.RommArtDownloader =
        dev.cannoli.scorza.romm.art.RommArtDownloader(http, paths)

    @Provides @Singleton
    fun provideRommArtFetcher(
        roms: dev.cannoli.scorza.db.RomsRepository,
        artwork: ArtworkLookup,
        db: RommDatabase,
        links: RommLinkRepository,
        store: RommConnectionStore,
        artDownloader: dev.cannoli.scorza.romm.art.RommArtDownloader,
        paths: CannoliPathsProvider,
        settings: dev.cannoli.scorza.settings.SettingsRepository,
    ): dev.cannoli.scorza.romm.art.RommArtFetcher = dev.cannoli.scorza.romm.art.RommArtFetcher(
        roms = roms,
        artwork = artwork,
        db = db,
        links = links,
        store = store,
        artDownloader = artDownloader,
        paths = paths,
        concurrency = { settings.concurrentDownloads },
        scope = CoroutineScope(SupervisorJob()),
    )

    @Provides @Singleton
    fun provideRommDownloader(
        client: RommClient,
        links: RommLinkRepository,
        artwork: ArtworkLookup,
        artDownloader: dev.cannoli.scorza.romm.art.RommArtDownloader,
        scanScheduler: ScanScheduler,
        store: RommConnectionStore,
        http: RommHttp,
        paths: CannoliPathsProvider,
        settings: dev.cannoli.scorza.settings.SettingsRepository,
    ): RommDownloader = RommDownloader(
        queue = RommDownloadQueue(),
        client = client,
        installer = RommInstaller(),
        links = links,
        artwork = artwork,
        artDownloader = artDownloader,
        scanScheduler = scanScheduler,
        store = store,
        http = http,
        paths = paths,
        concurrency = { settings.concurrentDownloads },
        scope = CoroutineScope(SupervisorJob()),
    )
}
