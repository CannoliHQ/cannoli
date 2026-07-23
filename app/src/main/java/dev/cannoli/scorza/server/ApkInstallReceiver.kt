package dev.cannoli.scorza.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.cannoli.scorza.util.KitchenLog
import javax.inject.Inject

@AndroidEntryPoint
class ApkInstallReceiver : BroadcastReceiver() {

    @Inject lateinit var installer: ApkInstaller

    override fun onReceive(context: Context, intent: Intent) {
        val installId = intent.getStringExtra(EXTRA_INSTALL_ID) ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    installer.fail(installId, "missing confirmation intent")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    KitchenLog.logError("apk confirm launch failed", e)
                    installer.fail(installId, "could not show install confirmation")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> installer.complete(installId)
            else -> installer.fail(
                installId,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed ($status)"
            )
        }
    }

    companion object {
        const val ACTION_RESULT = "dev.cannoli.scorza.server.APK_INSTALL_RESULT"
        const val EXTRA_INSTALL_ID = "install_id"
    }
}
