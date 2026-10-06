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

    private suspend fun holdOverlay(startedAt: Long) {
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed < MIN_OVERLAY_MILLIS) delay(MIN_OVERLAY_MILLIS - elapsed)
    }

    private companion object {
        const val MIN_OVERLAY_MILLIS = 900L
    }

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

    fun cancel() {
        job?.cancel()
        job = null
        nav.dialogState.value = DialogState.None
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
