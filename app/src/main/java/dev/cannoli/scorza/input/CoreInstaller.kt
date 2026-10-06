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
            val settled = downloader.state.first { items ->
                val row = items.firstOrNull { it.key == key }
                row == null || row.status == DownloadStatus.Done || row.status is DownloadStatus.Failed
            }
            val row = settled.firstOrNull { it.key == key }
            withContext(Dispatchers.Main) {
                when {
                    row?.status == DownloadStatus.Done -> onInstalled()
                    row == null -> Unit
                    else -> onFailed()
                }
            }
        }
    }
}
