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
import dev.cannoli.scorza.db.CannoliDatabase
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
import dev.cannoli.scorza.romm.sync.DeviceRegistrar
import dev.cannoli.scorza.romm.sync.LocalSaveResolver
import dev.cannoli.scorza.romm.sync.PendingConflictStore
import dev.cannoli.scorza.romm.sync.SaveBackupManager
import dev.cannoli.scorza.romm.sync.SaveSyncService
import dev.cannoli.scorza.romm.sync.SaveSyncStatusHolder
import dev.cannoli.scorza.romm.sync.SaveSyncStore
import dev.cannoli.scorza.romm.sync.SlotManager
import dev.cannoli.scorza.romm.sync.SyncHistoryStore
import dev.cannoli.scorza.romm.sync.SyncScheduler
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel
import dev.cannoli.scorza.util.ArtworkLookup
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RommModule {

    @Provides @Singleton
    fun provideRommHttp(store: RommConnectionStore): RommHttp =
        RommHttp(
            tokenProvider = { store.token },
            allowSelfSignedProvider = { store.allowSelfSigned },
            hostProvider = { store.host },
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
        rommStore: RommConnectionStore,
        @ApplicationContext context: Context,
    ): RommSyncCoordinator = RommSyncCoordinator(
        client, platformMap, db,
        enabledGroups = { rommStore.enabledCollectionGroups() },
        collectionsLabel = { context.getString(dev.cannoli.scorza.R.string.romm_sync_collections) },
    )

    @Provides @Singleton
    fun provideDeviceRegistrar(
        settings: SettingsRepository,
        client: RommClient,
    ): DeviceRegistrar = DeviceRegistrar(settings, client)

    @Provides @Singleton
    fun provideLocalSaveResolver(paths: CannoliPathsProvider): LocalSaveResolver =
        LocalSaveResolver(paths.root)

    @Provides @Singleton
    fun provideSaveBackupManager(paths: CannoliPathsProvider, resolver: LocalSaveResolver): SaveBackupManager =
        SaveBackupManager(paths.root, resolver)

    @Provides @Singleton
    fun provideSyncHistoryStore(db: CannoliDatabase): SyncHistoryStore = SyncHistoryStore(db)

    @Provides @Singleton
    fun providePendingConflictStore(db: CannoliDatabase): PendingConflictStore = PendingConflictStore(db)

    @Provides @Singleton
    fun provideSaveSyncStatusHolder(): SaveSyncStatusHolder = SaveSyncStatusHolder()

    @Provides @Singleton
    fun provideSaveSyncService(
        client: RommClient,
        connStore: RommConnectionStore,
        settings: SettingsRepository,
        registrar: DeviceRegistrar,
        store: SaveSyncStore,
        resolver: LocalSaveResolver,
        links: RommLinkRepository,
        paths: CannoliPathsProvider,
        backupManager: SaveBackupManager,
        history: SyncHistoryStore,
        pendingConflicts: PendingConflictStore,
        statusHolder: SaveSyncStatusHolder,
        matcher: dev.cannoli.scorza.romm.sync.RommCacheMatcher,
        roms: dev.cannoli.scorza.db.RomsRepository,
    ): SaveSyncService = SaveSyncService(client, connStore, settings, registrar, store, resolver, links, paths, backupManager, history, pendingConflicts, statusHolder, matcher, roms)

    @Provides @Singleton
    fun provideRommCacheMatcher(
        cache: RommDatabase,
    ): dev.cannoli.scorza.romm.sync.RommCacheMatcher = dev.cannoli.scorza.romm.sync.RommCacheMatcher(cache)

    @Provides @Singleton
    fun provideSlotManager(
        client: RommClient,
        store: SaveSyncStore,
        resolver: LocalSaveResolver,
        registrar: DeviceRegistrar,
        paths: CannoliPathsProvider,
        service: SaveSyncService,
    ): SlotManager = SlotManager(client, store, resolver, registrar, paths, service)

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
        client: RommClient,
        paths: CannoliPathsProvider,
        rommStore: RommConnectionStore,
    ): RommBrowseViewModel =
        RommBrowseViewModel(
            library = library,
            syncCoordinator = syncCoordinator,
            db = db,
            presentNamesFor = { tag -> romsRepository.presentFileNames(tag) },
            linkedIdsProvider = { linkRepository.presentRommIds() },
            hiddenTagsProvider = { settings.hiddenRommPlatforms },
            firmwareFor = { platformId -> client.getFirmware(platformId) },
            biosDirFor = { tag -> dev.cannoli.scorza.config.CannoliPaths(paths.root).biosFor(tag) },
            enabledCollectionGroups = { rommStore.enabledCollectionGroups() },
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
        @IoScope ioScope: CoroutineScope,
    ): dev.cannoli.scorza.romm.art.RommArtFetcher = dev.cannoli.scorza.romm.art.RommArtFetcher(
        roms = roms,
        artwork = artwork,
        db = db,
        links = links,
        store = store,
        artDownloader = artDownloader,
        paths = paths,
        concurrency = { settings.concurrentDownloads },
        scope = ioScope,
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
        @IoScope ioScope: CoroutineScope,
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
        scope = ioScope,
    )

    @Provides @Singleton
    fun provideSyncScheduler(
        @ApplicationContext context: Context,
        service: SaveSyncService,
        statusHolder: SaveSyncStatusHolder,
        platformConfig: PlatformConfig,
        settings: SettingsRepository,
        paths: CannoliPathsProvider,
        @IoScope ioScope: CoroutineScope,
    ): SyncScheduler = SyncScheduler(context, service, statusHolder, platformConfig, settings, { paths.romDir }, ioScope)
}
