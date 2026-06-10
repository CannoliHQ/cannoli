package dev.cannoli.scorza.di

import android.content.Context
import coil.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.romm.LiveRommLibrary
import dev.cannoli.scorza.romm.PlatformMap
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.romm.RommFolderScanner
import dev.cannoli.scorza.romm.RommHttp
import dev.cannoli.scorza.romm.RommImageLoader
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommSlugMap
import dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel
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
    fun provideRommLibrary(client: RommClient, platformMap: PlatformMap): RommLibrary =
        LiveRommLibrary(client, platformMap)

    @Provides @Singleton
    fun provideRommFolderScanner(paths: CannoliPathsProvider): RommFolderScanner =
        RommFolderScanner { paths.romDir }

    @Provides @Singleton
    fun provideRommBrowseViewModel(
        library: RommLibrary,
        scanner: RommFolderScanner,
    ): RommBrowseViewModel =
        RommBrowseViewModel(library) { tag -> scanner.localFiles(tag) }

    @Provides @Singleton
    fun provideRommImageLoader(
        @ApplicationContext context: Context,
        http: RommHttp,
        paths: CannoliPathsProvider,
    ): ImageLoader =
        RommImageLoader.build(context, http, java.io.File(dev.cannoli.scorza.config.CannoliPaths(paths.root).configCache, "RommArt"))
}
