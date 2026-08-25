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
    private val osdController: OsdController,
) {
    private var job: Job? = null

    /**
     * The cost, stated before anything downloads. A fresh build is close enough in size to the one
     * on disk that summing what is installed answers it without touching the network.
     */
    fun confirm() {
        val installed = installedCoreService.embeddedCores()
        if (installed.isEmpty()) return
        nav.dialogState.value = DialogState.UpdateCoresConfirm(
            cores = installed.size,
            bytes = coreDownloadService.estimatedBytes(installed),
        )
    }

    /**
     * Nothing is compared locally: each request carries the etag recorded at install time, so an
     * unchanged build answers 304 and transfers nothing.
     */
    fun start() {
        val installed = installedCoreService.embeddedCores()
        if (installed.isEmpty() || job?.isActive == true) return
        nav.dialogState.value = DialogState.UpdatingCores
        job = ioScope.launch {
            val summary = coreDownloadService.updateAll(installed)
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
