package dev.cannoli.scorza.launcher

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstalledCoreService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun embeddedCores(): Set<String> =
        File(context.filesDir, "cores").listFiles()
            ?.map { it.name }
            ?.filter { it.endsWith("_android.so") }
            ?.map { it.removeSuffix("_android.so") }
            ?.toSet()
            ?: emptySet()

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
    }
}
