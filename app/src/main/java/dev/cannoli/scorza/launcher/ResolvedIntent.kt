package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.net.Uri

data class ResolvedIntent(
    val component: ComponentName?,
    val packageName: String?,
    val action: String,
    val dataUri: Uri?,
    val mimeType: String?,
    val extras: List<ResolvedExtra>,
)

sealed class ResolvedExtra {
    abstract val key: String
    data class StringExtra(override val key: String, val value: String) : ResolvedExtra()
    data class IntExtra(override val key: String, val value: Int) : ResolvedExtra()
    data class BoolExtra(override val key: String, val value: Boolean) : ResolvedExtra()
    data class UriExtra(override val key: String, val value: Uri) : ResolvedExtra()
    data class StringArrayExtra(override val key: String, val values: List<String>) : ResolvedExtra()
}
