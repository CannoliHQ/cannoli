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
import dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries
import dev.cannoli.scorza.input.repo.MappingRepository
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

    @Provides
    @Singleton
    fun provideMappingRepository(paths: CannoliPathsProvider): MappingRepository =
        MappingRepository { CannoliPaths(paths.root).configInputMappings }

    @Provides
    @Singleton
    fun provideBundledAutoconfigEntries(
        @ApplicationContext context: Context,
    ): BundledAutoconfigEntries =
        BundledAutoconfigEntries(AutoconfigLoader(AssetCfgSource(context)).entries())

    @Provides
    @Singleton
    fun provideMappingResolver(
        repository: MappingRepository,
        bundled: BundledAutoconfigEntries,
        paths: CannoliPathsProvider,
        hints: dev.cannoli.scorza.input.hints.ControllerHintTable,
    ): MappingResolver = MappingResolver(
        repository = repository,
        bundledRetroArchEntries = bundled,
        hints = hints,
        mappingsDir = CannoliPaths(paths.root).configInputMappings,
    )

    @Provides
    @Singleton
    fun provideControllerHintTable(
        @ApplicationContext context: Context,
    ): dev.cannoli.scorza.input.hints.ControllerHintTable =
        dev.cannoli.scorza.input.hints.ControllerHintTable.fromAssets(context)

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
        mappingRepository: MappingRepository,
        blacklist: dev.cannoli.scorza.input.ControllerBlacklist,
        bundled: BundledAutoconfigEntries,
        hints: dev.cannoli.scorza.input.hints.ControllerHintTable,
    ): ControllerBridge = ControllerBridge(
        resolver = resolver,
        portRouter = portRouter,
        activeMappingHolder = activeMappingHolder,
        mappingRepository = mappingRepository,
        blacklist = blacklist,
        bundledCfgs = bundled,
        hints = hints,
        devKeyboardEnabled = dev.cannoli.scorza.BuildConfig.DEBUG &&
            dev.cannoli.scorza.util.DeviceType.isAvd(),
    )
}
