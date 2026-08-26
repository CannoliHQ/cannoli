package dev.cannoli.scorza.launcher

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the embedded RetroArch can load.
 *
 * This used to also query every separately installed RetroArch over a broadcast, cache what each
 * one answered, and classify the ones that could not answer. That whole apparatus served the
 * external source, which no longer exists: the remaining runner keeps its cores in a directory this
 * app owns, so presence is a file check that cannot fail or go stale. The boot-time package scan
 * went with it.
 */
@Singleton
class InstalledCoreService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // Core ids, not filenames: the result is compared against a resolved core id, so returning
    // "mgba_libretro_android.so" here would never match "mgba_libretro".
    fun embeddedCores(): Set<String> =
        File(context.filesDir, "cores").listFiles()
            ?.map { it.name }
            ?.filter { it.endsWith("_android.so") }
            ?.map { it.removeSuffix("_android.so") }
            ?.toSet()
            ?: emptySet()

    /**
     * Deletes the core's binary, which is the whole of an uninstall: embeddedCores() lists the
     * directory, so a core with no file simply stops being installed and leaves nothing stale
     * behind. Its stamp goes too, so nothing describes a file that is gone.
     *
     * System files are deliberately untouched. Those live on the SD card, are shared between
     * cores, and are the user's own data rather than something this download put there.
     */
    fun uninstall(coreId: String): Boolean {
        val file = File(File(context.filesDir, "cores"), "${coreId}_android.so")
        val gone = !file.exists() || file.delete()
        if (gone) DownloadStamps.remove(context.filesDir, EmbeddedCoreDownloader.soUrlFor(coreId))
        return gone
    }

    companion object {
        private val PACKAGE_LABELS = mapOf(
            "dev.cannoli.ricotta.aarch64" to "RicottaArch",
            "dev.cannoli.ricotta" to "RicottaArch",
            "com.retroarch.aarch64" to "RetroArch",
            "com.retroarch" to "RetroArch"
        )

        fun getPackageLabel(pkg: String): String = PACKAGE_LABELS[pkg] ?: pkg

        fun soToCoreId(filename: String): String =
            filename.removeSuffix("_android.so").removeSuffix(".so")
    }
}
