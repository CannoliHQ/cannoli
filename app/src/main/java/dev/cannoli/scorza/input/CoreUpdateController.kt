package dev.cannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.launcher.CoreDownloadService
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.ui.components.OsdController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Replacing every installed core: the confirmation, the run, and cancelling it.
 *
 * Its own class rather than part of the settings handler, because the run outlives the screen that
 * starts it and cancelling arrives through the dialog handler. Putting it in either would make one
 * depend on the other, and the dependency already runs the other way.
 */
@ActivityScoped
class CoreUpdateController @Inject constructor(
    @IoScope private val ioScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val nav: NavigationController,
    private val installedCoreService: InstalledCoreService,
    private val coreDownloadService: CoreDownloadService,
    private val settingsViewModel: SettingsViewModel,
    private val settings: dev.cannoli.scorza.settings.SettingsRepository,
    private val osdController: OsdController,
) {
    private var job: Job? = null

    /**
     * Keeps an overlay up long enough to be read. Both of these used to take seconds of network and
     * now often take none: the check is one index request, and a pass where every core matches
     * replaces nothing. Shown and dismissed inside a couple of frames they read as a flicker rather
     * than as an answer. Only the remainder is waited, so work that genuinely takes time is never
     * slowed down.
     */
    private suspend fun holdOverlay(startedAt: Long) {
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed < MIN_OVERLAY_MILLIS) delay(MIN_OVERLAY_MILLIS - elapsed)
    }

    private companion object {
        const val MIN_OVERLAY_MILLIS = 900L
    }

    /**
     * The cost, stated before anything downloads, and measured rather than estimated. The buildbot
     * publishes a CRC of each core's binary, so comparing it against what was recorded at install
     * time says exactly which cores differ, and a HEAD on those says what they weigh.
     *
     * Nothing to fetch is reported and nothing is shown: a pass that would replace no bytes used to
     * open a progress dialog and close it a few frames later, which read as a flicker.
     */
    fun confirm() {
        val installed = installedCoreService.embeddedCores()
        if (installed.isEmpty()) return
        if (job?.isActive == true) return
        nav.dialogState.value = DialogState.CheckingCores
        job = ioScope.launch {
            val startedAt = System.currentTimeMillis()
            val pre = coreDownloadService.preflight(installed)
            holdOverlay(startedAt)
            withContext(Dispatchers.Main) {
                job = null
                if (pre.stale.isEmpty()) {
                    nav.dialogState.value = DialogState.None
                    osdController.show(context.getString(R.string.osd_cores_current))
                    settings.lastCoreUpdate = java.time.LocalDate.now().toString()
                    settings.lastCoreUpdateCompleted = true
                    settingsViewModel.refreshItemsAndSettings()
                } else {
                    nav.dialogState.value = DialogState.UpdateCoresConfirm(
                        cores = pre.stale.size,
                        bytes = pre.bytes,
                    )
                }
            }
        }
    }

    /**
     * Revalidates every installed core, not just the ones the pre-flight named. The pre-flight
     * answers what to tell the user; the pass still checks each core for itself, so a core that
     * changed between the two, or one the index said nothing about, is not skipped on the strength
     * of a message written a moment earlier.
     */
    fun start() {
        val installed = installedCoreService.embeddedCores()
        if (installed.isEmpty() || job?.isActive == true) return
        nav.dialogState.value = DialogState.UpdatingCores
        job = ioScope.launch {
            val startedAt = System.currentTimeMillis()
            val summary = coreDownloadService.updateAll(installed)
            holdOverlay(startedAt)
            withContext(Dispatchers.Main) {
                job = null
                nav.dialogState.value = DialogState.None
                settingsViewModel.refreshItemsAndSettings()
                osdController.show(report(summary))
            }
        }
    }

    /**
     * Stops before the next core rather than mid-write. Cores already replaced stay replaced, which
     * is safe because each is staged and renamed, so a cancelled run leaves some newer and none
     * broken. The last-run date is not recorded, since the run did not finish.
     */
    fun cancel() {
        // Covers the pre-flight as well as the run: both use the same slot, and back during the
        // check has to leave as cleanly as back during the download.
        job?.cancel()
        job = null
        nav.dialogState.value = DialogState.None
        // The run records that it stopped, so the row has to be rebuilt or it keeps showing the
        // outcome of whichever run finished last.
        settingsViewModel.refreshItemsAndSettings()
    }

    private fun report(summary: CoreDownloadService.UpdateSummary): String = when {
        summary.failed > 0 -> context.resources.getQuantityString(
            R.plurals.osd_cores_update_failed, summary.failed, summary.failed
        )
        summary.updated > 0 -> context.resources.getQuantityString(
            R.plurals.osd_cores_updated, summary.updated, summary.updated
        )
        else -> context.getString(R.string.osd_cores_current)
    }
}
