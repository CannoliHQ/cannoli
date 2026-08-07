package dev.cannoli.scorza.launcher

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class CoreDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Result(val kind: String, val core: String?, val ok: Boolean, val error: String?)

    suspend fun downloadCore(coreId: String, forceInfoRefresh: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            EmbeddedCoreDownloader.download(context, coreId, forceInfoRefresh)
        }
}
