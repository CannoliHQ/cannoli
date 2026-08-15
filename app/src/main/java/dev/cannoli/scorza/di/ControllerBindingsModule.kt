package dev.cannoli.scorza.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.input.autoconfig.AssetCfgSource
import dev.cannoli.scorza.input.autoconfig.AutoconfigLoader
import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import dev.cannoli.scorza.input.autoconfig.AutoconfigSeeder
import dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries
import dev.cannoli.scorza.input.autoconfig.CfgSource
import dev.cannoli.scorza.input.resolver.MappingResolver
import dev.cannoli.scorza.input.runtime.ActiveMappingHolder
import dev.cannoli.scorza.input.runtime.ControllerBridge
import dev.cannoli.scorza.input.runtime.PortRouter
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BundledRetroArchAutoconfig

@Module
@InstallIn(SingletonComponent::class)
object ControllerBindingsModule {

    // Only Cannoli's curated database is bundled (fetched from CannoliHQ/input-db into cannoli/).
    // Pads without a curated entry fall to the setup wizard and the Android default at resolve time.
    private fun bundledCfgSource(context: Context): CfgSource =
        AssetCfgSource(context, "autoconfig/cannoli")

    @Provides
    @Singleton
    fun provideBundledAutoconfigEntries(
        @ApplicationContext context: Context,
    ): BundledAutoconfigEntries =
        BundledAutoconfigEntries(AutoconfigLoader(bundledCfgSource(context)).entries())

    @Provides
    @Singleton
    fun provideAutoconfigRepository(paths: CannoliPathsProvider): AutoconfigRepository =
        AutoconfigRepository(
            dirProvider = { CannoliPaths(paths.root).configInputAutoconfigAndroid },
            debugBuild = dev.cannoli.scorza.BuildConfig.DEBUG,
        )

    @Provides
    @Singleton
    fun provideAutoconfigSeeder(
        @ApplicationContext context: Context,
        paths: CannoliPathsProvider,
    ): AutoconfigSeeder = AutoconfigSeeder(
        source = bundledCfgSource(context),
        targetDirProvider = { CannoliPaths(paths.root).configInputAutoconfigAndroid },
        legacyMappingsDirProvider = { CannoliPaths(paths.root).configInputMappings },
        versionCode = dev.cannoli.scorza.BuildConfig.VERSION_CODE,
    )

    @Provides
    @Singleton
    fun provideMappingResolver(
        diskRepository: AutoconfigRepository,
        bundled: BundledAutoconfigEntries,
    ): MappingResolver = MappingResolver(
        diskRepository = diskRepository,
        bundledRetroArchEntries = bundled,
    )

    @Provides
    @Singleton
    fun providePortRouter(): PortRouter = PortRouter()

    @Provides
    @Singleton
    fun provideActiveMappingHolder(): ActiveMappingHolder = ActiveMappingHolder()

    @Provides
    @Singleton
    fun provideControllerBridge(
        resolver: MappingResolver,
        portRouter: PortRouter,
        activeMappingHolder: ActiveMappingHolder,
        autoconfigRepository: AutoconfigRepository,
        blacklist: dev.cannoli.scorza.input.ControllerBlacklist,
    ): ControllerBridge = ControllerBridge(
        resolver = resolver,
        portRouter = portRouter,
        activeMappingHolder = activeMappingHolder,
        autoconfigRepository = autoconfigRepository,
        blacklist = blacklist,
        devKeyboardEnabled = dev.cannoli.scorza.BuildConfig.DEBUG &&
            dev.cannoli.scorza.util.DeviceType.isAvd(),
    )
}
