package dev.cannoli.scorza.launcher

sealed interface LaunchResult {
    data object Success : LaunchResult
    data class AppNotInstalled(val packageName: String) : LaunchResult

    data object NoEmulatorSet : LaunchResult
    data class Error(val message: String) : LaunchResult
}
