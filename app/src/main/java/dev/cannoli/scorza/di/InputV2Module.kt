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
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.repo.MappingRepository
import dev.cannoli.scorza.input.v2.resolver.MappingResolver
import dev.cannoli.scorza.input.v2.runtime.ActiveMappingHolder
import dev.cannoli.scorza.input.v2.runtime.BluetoothPhysicalIdentityResolver
import dev.cannoli.scorza.input.v2.runtime.ControllerV2Bridge
import dev.cannoli.scorza.input.v2.runtime.PhysicalIdentityResolver
import dev.cannoli.scorza.input.v2.runtime.PortRouter
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BundledRetroArchAutoconfig

@Module
@InstallIn(SingletonComponent::class)
object InputV2Module {

    @Provides
    @Singleton
    fun provideMappingRepository(@CannoliRoot root: File): MappingRepository =
        MappingRepository(CannoliPaths(root).configInputMappings)

    @Provides
    @Singleton
    @BundledRetroArchAutoconfig
    fun provideBundledAutoconfigEntries(
        @ApplicationContext context: Context,
    ): List<RetroArchCfgEntry> =
        AutoconfigLoader(AssetCfgSource(context)).entries()

    @Provides
    @Singleton
    fun provideMappingResolver(
        repository: MappingRepository,
        @BundledRetroArchAutoconfig bundled: List<RetroArchCfgEntry>,
        @CannoliRoot root: File,
        hints: dev.cannoli.scorza.input.v2.hints.ControllerHintTable,
    ): MappingResolver = MappingResolver(
        repository = repository,
        bundledRetroArchEntries = bundled,
        hints = hints,
        mappingsDir = CannoliPaths(root).configInputMappings,
    )

    @Provides
    @Singleton
    fun provideControllerHintTable(
        @ApplicationContext context: Context,
    ): dev.cannoli.scorza.input.v2.hints.ControllerHintTable =
        dev.cannoli.scorza.input.v2.hints.ControllerHintTable.fromAssets(context)

    @Provides
    @Singleton
    fun providePortRouter(): PortRouter = PortRouter()

    @Provides
    @Singleton
    fun provideActiveMappingHolder(): ActiveMappingHolder = ActiveMappingHolder()

    @Provides
    @Singleton
    fun providePhysicalIdentityResolver(
        @ApplicationContext context: Context,
    ): PhysicalIdentityResolver = BluetoothPhysicalIdentityResolver(context)

    @Provides
    @Singleton
    fun provideControllerV2Bridge(
        resolver: MappingResolver,
        portRouter: PortRouter,
        activeMappingHolder: ActiveMappingHolder,
        physicalIdentityResolver: PhysicalIdentityResolver,
        @BundledRetroArchAutoconfig bundled: List<RetroArchCfgEntry>,
        hints: dev.cannoli.scorza.input.v2.hints.ControllerHintTable,
    ): ControllerV2Bridge = ControllerV2Bridge(
        resolver = resolver,
        portRouter = portRouter,
        activeMappingHolder = activeMappingHolder,
        physicalIdentityResolver = physicalIdentityResolver,
        bundledCfgs = bundled,
        hints = hints,
    )
}
