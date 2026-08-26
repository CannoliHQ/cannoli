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

/**
 * Says when a transfer lands, for anyone not watching the queue at the time.
 *
 * A download used to finish in silence unless its caller happened to say so, which is fine while
 * the queue screen is open and useless everywhere else. This watches the shared state and reports
 * each row that reaches a terminal status once.
 */
@ActivityScoped
class DownloadOutcomeReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: Downloader,
    private val osdController: OsdController,
    private val nav: NavigationController,
) {
    /** Terminal statuses already announced, so a row is reported once rather than on every emission. */
    private val reported = mutableSetOf<String>()

    fun start(scope: CoroutineScope) {
        scope.launch {
            downloader.state.collect { items ->
                val live = items.map { it.key }.toSet()
                // A cleared or cancelled row can be enqueued again later, and its outcome then is
                // news again.
                reported.retainAll(live)

                val landed = items.filter {
                    it.key !in reported &&
                        (it.status == DownloadStatus.Done || it.status is DownloadStatus.Failed)
                }
                if (landed.isEmpty()) return@collect
                reported.addAll(landed.map { it.key })

                // Silent while the queue is on screen: the row already changed in front of the
                // user, and a pill repeating it would cover the list they are reading.
                if (nav.dialogState.value is DialogState.RommDownloads) return@collect

                withContext(Dispatchers.Main) {
                    val failed = landed.filter { it.status is DownloadStatus.Failed }
                    val done = landed.size - failed.size
                    val message = when {
                        // Several can land between emissions, and naming them all overflows the
                        // pill, so past one it becomes a count.
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
