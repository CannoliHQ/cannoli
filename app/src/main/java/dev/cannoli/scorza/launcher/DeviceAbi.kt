package dev.cannoli.scorza.launcher

import android.os.Build

object DeviceAbi {
    fun primary(): String =
        Build.SUPPORTED_ABIS?.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "arm64-v8a"
}
