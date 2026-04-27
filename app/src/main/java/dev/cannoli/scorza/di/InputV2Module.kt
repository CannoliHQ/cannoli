package dev.cannoli.scorza.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.input.autoconfig.AssetCfgSource
import dev.cannoli.scorza.input.autoconfig.AutoconfigLoader
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.repo.TemplateRepository
import dev.cannoli.scorza.input.v2.resolver.NoopPaddleboatTemplateImporter
import dev.cannoli.scorza.input.v2.resolver.PaddleboatTemplateImporter
import dev.cannoli.scorza.input.v2.resolver.TemplateResolver
import dev.cannoli.scorza.input.v2.runtime.ActiveTemplateHolder
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
    fun provideTemplateRepository(@CannoliRoot root: File): TemplateRepository =
        TemplateRepository(CannoliPaths(root).configInputTemplates)

    @Provides
    @Singleton
    @BundledRetroArchAutoconfig
    fun provideBundledAutoconfigEntries(
        @ApplicationContext context: Context,
    ): List<RetroArchCfgEntry> =
        AutoconfigLoader(AssetCfgSource(context)).entries()

    @Provides
    @Singleton
    fun provideTemplateResolver(
        repository: TemplateRepository,
        paddleboat: PaddleboatTemplateImporter,
        @BundledRetroArchAutoconfig bundled: List<RetroArchCfgEntry>,
        @CannoliRoot root: File,
    ): TemplateResolver = TemplateResolver(
        repository = repository,
        paddleboatImporter = paddleboat,
        bundledRetroArchEntries = bundled,
        templatesDir = CannoliPaths(root).configInputTemplates,
    )

    @Provides
    @Singleton
    fun providePortRouter(): PortRouter = PortRouter()

    @Provides
    @Singleton
    fun provideActiveTemplateHolder(): ActiveTemplateHolder = ActiveTemplateHolder()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class InputV2BindingsModule {

    @Binds
    @Singleton
    abstract fun bindPaddleboatImporter(impl: NoopPaddleboatTemplateImporter): PaddleboatTemplateImporter
}
