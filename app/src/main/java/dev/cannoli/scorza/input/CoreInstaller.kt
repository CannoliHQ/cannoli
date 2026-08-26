package dev.cannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.download.DownloadKind
import dev.cannoli.scorza.download.DownloadStatus
import dev.cannoli.scorza.download.Downloader
import dev.cannoli.scorza.launcher.CoreDownloadHandler
import dev.cannoli.ui.components.OsdController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class CoreInstaller @Inject constructor(
    @IoScope private val ioScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val downloader: Downloader,
    private val osdController: OsdController,
) {
    /**
     * Queues a core and reports when it lands.
     *
     * This used to run the download itself behind a two minute OSD, which said only that something
     * was happening: several at once left the pill fighting over which to describe, and a slow one
     * looked stuck. It goes through the shared queue now, so it gets progress, ordering and a row
     * on the Downloads screen like anything else, and this only has to wait for the outcome.
     *
     * [onFailed] runs when the download does not land. A caller that was mid-launch needs to say so
     * in something that persists: an OSD is transient, so a user who looks away sees a game that
     * simply never started.
     */
    fun downloadCore(
        pkg: String,
        coreId: String,
        coreName: String,
        onFailed: () -> Unit = {},
        onInstalled: () -> Unit,
    ) {
        val key = CoreDownloadHandler.keyFor(coreId)
        downloader.enqueue(listOf(
            DownloadItem(key = key, displayName = coreName, kind = DownloadKind.CORE, payload = coreId)
        ))
        dev.cannoli.scorza.download.DownloadManager.ensureStarted(context)
        osdController.show(context.getString(R.string.osd_core_queued, coreName))

        ioScope.launch {
            // Settled means Done, Failed, or gone: the queue drops a cancelled item outright, and
            // waiting for a terminal status it will never publish would hang this forever.
            val settled = downloader.state.first { items ->
                val row = items.firstOrNull { it.key == key }
                row == null || row.status == DownloadStatus.Done || row.status is DownloadStatus.Failed
            }
            val row = settled.firstOrNull { it.key == key }
            withContext(Dispatchers.Main) {
                when {
                    row?.status == DownloadStatus.Done -> onInstalled()
                    row == null -> Unit // cancelled by the user, who does not need telling
                    // The failure OSD is DownloadOutcomeReporter's now, for every kind at once.
                    // This only has to run the caller's recovery, which for a launch is a message
                    // that persists rather than a pill that fades.
                    else -> onFailed()
                }
            }
        }
    }
}
