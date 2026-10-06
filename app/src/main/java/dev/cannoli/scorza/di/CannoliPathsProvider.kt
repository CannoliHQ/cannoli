package dev.cannoli.scorza.di

import dev.cannoli.scorza.settings.SettingsRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CannoliPathsProvider @Inject constructor(
    private val settings: SettingsRepository,
) {
    val root: File get() = File(settings.sdCardRoot)

    val rootOrNull: File? get() = settings.sdCardRootOrNull?.let { File(it) }

    val romDir: File
        get() = settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(root, "Roms")
}
