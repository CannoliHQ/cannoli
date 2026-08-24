package dev.cannoli.scorza.launcher

sealed interface LaunchResult {
    data object Success : LaunchResult
    data class CoreNotInstalled(val coreName: String) : LaunchResult
    data class AppNotInstalled(val packageName: String) : LaunchResult

    /** The platform resolves to nothing runnable, as opposed to something that is not installed. */
    data object NoEmulatorSet : LaunchResult
    data class Error(val message: String) : LaunchResult
}
