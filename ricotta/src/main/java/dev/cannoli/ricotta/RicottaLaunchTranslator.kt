package dev.cannoli.ricotta

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.cannoli.igm.RicottaLaunchParams
import java.io.File

object RicottaLaunchTranslator {

    // filesDir/cores, matching LaunchManager.findEmbeddedCore, InstalledCoreService.localCores
    // and EmbeddedCoreDownloader. RicottaArch used dataDir/cores, which is a different directory.
    fun coreLibPath(context: Context, coreId: String): String =
        File(context.filesDir, "cores/${coreId}_android.so").absolutePath

    fun toRetroIntent(context: Context, params: RicottaLaunchParams): Intent =
        Intent().apply {
            component = ComponentName(
                context.packageName,
                "com.retroarch.browser.retroactivity.RetroActivityFuture",
            )
            putExtra("LIBRETRO", coreLibPath(context, params.coreId))
            putExtra("ROM", params.romPath)
            params.configFilePath?.let { putExtra("CONFIGFILE", it) }
            if (params.quitOnFocusLoss) putExtra("QUITFOCUS", true)
            params.preferredRefreshRate?.let { putExtra("REFRESH", it.toString()) }
            params.writeToIntent(this)
        }
}
