package dev.cannoli.scorza.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume

class CoreDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val handler = Handler(Looper.getMainLooper())

    data class Result(val kind: String, val core: String?, val ok: Boolean, val error: String?)

    // Empty (unset) or the app's own package both mean the in-APK RetroArch.
    private fun isEmbedded(pkg: String): Boolean = pkg.isEmpty() || pkg == context.packageName

    suspend fun downloadCore(
        pkg: String,
        coreId: String,
        forceInfoRefresh: Boolean = false,
        timeoutMs: Long = 120_000L,
    ): Result {
        // The broadcast exists to ask another app to fetch a core. For the in-APK RetroArch
        // there is nobody to ask: this app does the download. Broadcasting here would wait out
        // the full timeout for a reply that can never come.
        if (isEmbedded(pkg)) {
            return withContext(Dispatchers.IO) {
                EmbeddedCoreDownloader.download(context, coreId, forceInfoRefresh)
            }
        }
        return awaitDownload(pkg, coreId, forceInfoRefresh, timeoutMs)
    }

    private suspend fun awaitDownload(
        pkg: String,
        coreId: String,
        forceInfoRefresh: Boolean,
        timeoutMs: Long,
    ): Result = await(
        send = {
            Intent(ACTION_DOWNLOAD).apply {
                setPackage(pkg)
                putExtra(EXTRA_CORE, coreId)
                if (forceInfoRefresh) putExtra(EXTRA_FORCE_INFO, true)
            }
        },
        matches = { it.getStringExtra(EXTRA_KIND) == "core" && it.getStringExtra(EXTRA_CORE) == coreId },
        timeoutMs = timeoutMs,
        timeoutResult = { Result("core", coreId, false, "timeout") },
    )

    private suspend fun await(
        send: () -> Intent,
        matches: (Intent) -> Boolean,
        timeoutMs: Long,
        timeoutResult: () -> Result,
    ): Result = suspendCancellableCoroutine { cont ->
        val token = Any()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!matches(intent)) return
                handler.removeCallbacksAndMessages(token)
                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                if (cont.isActive) {
                    cont.resume(
                        Result(
                            kind = intent.getStringExtra(EXTRA_KIND) ?: "unknown",
                            core = intent.getStringExtra(EXTRA_CORE),
                            ok = intent.getBooleanExtra(EXTRA_OK, false),
                            error = intent.getStringExtra(EXTRA_ERROR),
                        )
                    )
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(ACTION_RESULT), Context.RECEIVER_EXPORTED)
        context.sendBroadcast(send())
        handler.postAtTime({
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            if (cont.isActive) cont.resume(timeoutResult())
        }, token, android.os.SystemClock.uptimeMillis() + timeoutMs)
        cont.invokeOnCancellation {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            handler.removeCallbacksAndMessages(token)
        }
    }

    companion object {
        const val ACTION_DOWNLOAD = "dev.cannoli.ricotta.DOWNLOAD_CORE"
        const val ACTION_RESULT = "dev.cannoli.ricotta.DOWNLOAD_CORE_RESULT"
        const val EXTRA_CORE = "CORE"
        const val EXTRA_FORCE_INFO = "FORCE_INFO_REFRESH"
        const val EXTRA_KIND = "KIND"
        const val EXTRA_OK = "OK"
        const val EXTRA_ERROR = "ERROR"
    }
}
