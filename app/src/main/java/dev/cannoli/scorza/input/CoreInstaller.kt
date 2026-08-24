package dev.cannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.launcher.CoreDownloadService
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.ui.components.OsdController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class CoreInstaller @Inject constructor(
    @IoScope private val ioScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val coreDownloadService: CoreDownloadService,
    private val installedCoreService: InstalledCoreService,
    private val osdController: OsdController,
) {
    /**
     * [onFailed] runs when the download does not land. A caller that was mid-launch needs to say so
     * in something that persists: the failure OSD is transient, so a user who looks away sees a
     * game that simply never started.
     */
    fun downloadCore(
        pkg: String,
        coreId: String,
        coreName: String,
        onFailed: () -> Unit = {},
        onInstalled: () -> Unit,
    ) {
        osdController.show(
            context.getString(R.string.osd_downloading_core, coreName),
            durationMs = 120_000L,
        )
        ioScope.launch {
            val result = coreDownloadService.downloadCore(coreId)
            withContext(Dispatchers.Main) {
                if (result.ok) {
                    osdController.show(context.getString(R.string.osd_core_downloaded, coreName))
                    onInstalled()
                } else {
                    val err = result.error ?: context.getString(R.string.osd_core_download_unknown_error)
                    osdController.show(context.getString(R.string.osd_core_download_failed, err))
                    onFailed()
                }
            }
        }
    }
}
