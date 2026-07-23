package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmuLauncher @Inject constructor(@ApplicationContext private val context: Context) {

    fun launch(romFile: File, packageName: String, activityName: String, action: String): LaunchResult {
        if (!context.isPackageInstalled(packageName)) {
            return LaunchResult.AppNotInstalled(packageName)
        }

        val uri = context.romFileProviderUri(romFile)

        val intent = Intent(action).apply {
            setDataAndType(uri, "*/*")
            component = ComponentName(packageName, activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return context.startActivityNoAnim(intent, "Failed to launch emulator")
    }
}
