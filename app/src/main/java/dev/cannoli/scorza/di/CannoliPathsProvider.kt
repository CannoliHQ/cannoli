package dev.cannoli.scorza.di

import dev.cannoli.scorza.settings.SettingsRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the Cannoli root (and ROM directory) on demand from [SettingsRepository], rather than
 * capturing a [File] at construction time. Storage-backed singletons must read through this so they
 * pick up the SD-card root that the setup flow writes after MANAGE_EXTERNAL_STORAGE is granted,
 * instead of freezing the default path during Hilt's eager field injection in MainActivity.onCreate.
 */
@Singleton
class CannoliPathsProvider @Inject constructor(
    private val settings: SettingsRepository,
) {
    val root: File get() = File(settings.sdCardRoot)

    /** Null until first run's storage step resolves. See [SettingsRepository.sdCardRootOrNull]. */
    val rootOrNull: File? get() = settings.sdCardRootOrNull?.let { File(it) }

    val romDir: File
        get() = settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(root, "Roms")
}
