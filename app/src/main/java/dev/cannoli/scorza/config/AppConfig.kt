package dev.cannoli.scorza.config

import android.content.Intent

data class AppConfig(
    val packageName: String,
    val activity: String? = null,
    val action: String = Intent.ACTION_VIEW,
    val data: DataBinding = DataBinding.None,
    val extras: List<ExtraSpec> = emptyList(),
    val mimeType: String? = "*/*",
    val intentFlags: Int = Intent.FLAG_ACTIVITY_NEW_TASK,
    val launchMethod: LaunchMethod = LaunchMethod.INTENT,
)

sealed class DataBinding {
    data object None : DataBinding()
    data class FileProvider(val grantPermission: Boolean = true) : DataBinding()
    data object AbsolutePath : DataBinding()
    data object ExternalStorageSaf : DataBinding()
    data class CustomScheme(val scheme: String, val authority: String) : DataBinding()
}

data class ExtraSpec(val key: String, val kind: ExtraValueKind, val values: List<String>? = null)

enum class ExtraValueKind {
    FILE_PATH,
    FILE_URI_PARCELABLE,
    FILE_URI_STRING,
    STRING_ARRAY,
}

enum class LaunchMethod { INTENT, SHELL }
