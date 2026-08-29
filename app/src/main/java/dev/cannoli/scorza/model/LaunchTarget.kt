package dev.cannoli.scorza.model

sealed interface LaunchTarget {
    data object RetroArch : LaunchTarget

    data class ApkLaunch(
        val packageName: String
    ) : LaunchTarget
}
