package dev.cannoli.scorza.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.ui.components.OsdController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class DownloadOutcomeReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: Downloader,
    private val osdController: OsdController,
    private val nav: NavigationController,
) {
    private val reported = mutableSetOf<String>()

    fun start(scope: CoroutineScope) {
        scope.launch {
            downloader.state.collect { items ->
                val live = items.map { it.key }.toSet()
                reported.retainAll(live)

                val landed = items.filter {
                    it.key !in reported &&
                        (it.status == DownloadStatus.Done || it.status is DownloadStatus.Failed)
                }
                if (landed.isEmpty()) return@collect
                reported.addAll(landed.map { it.key })

                if (nav.dialogState.value is DialogState.RommDownloads) return@collect

                withContext(Dispatchers.Main) {
                    val failed = landed.filter { it.status is DownloadStatus.Failed }
                    val done = landed.size - failed.size
                    val message = when {
                        failed.isNotEmpty() && failed.size == landed.size && landed.size == 1 ->
                            context.getString(R.string.osd_download_failed_one, landed.first().displayName)
                        failed.isNotEmpty() ->
                            context.resources.getQuantityString(
                                R.plurals.osd_download_failed_many, failed.size, failed.size
                            )
                        landed.size == 1 ->
                            context.getString(R.string.osd_download_done_one, landed.first().displayName)
                        else ->
                            context.resources.getQuantityString(
                                R.plurals.osd_download_done_many, done, done
                            )
                    }
                    osdController.show(message)
                }
            }
        }
    }
}
