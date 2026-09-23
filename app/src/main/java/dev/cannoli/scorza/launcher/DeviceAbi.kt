package dev.cannoli.scorza.launcher

import android.os.Build

/**
 * The ABI Cannoli builds and downloads cores for. The buildbot only publishes arm64-v8a and
 * armeabi-v7a, so any other primary ABI (x86, x86_64) falls back to arm64-v8a rather than
 * matching nothing.
 */
object DeviceAbi {
    fun primary(): String =
        Build.SUPPORTED_ABIS?.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "arm64-v8a"
}
