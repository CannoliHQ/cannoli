package dev.cannoli.scorza.config

import dev.cannoli.scorza.R

enum class EmulatorSource(val displayName: String) {
    Internal("Internal"),
    RetroArch("RetroArch"),
    Standalone("Standalone");

    val emptyMessageRes: Int
        get() = when (this) {
            Internal, RetroArch -> R.string.value_no_cores_found
            Standalone -> R.string.value_none_installed
        }

    companion object {
        fun fromRunnerLabel(label: String?): EmulatorSource? = when (label) {
            "Internal" -> Internal
            "Standalone", "App" -> Standalone
            null, "" -> null
            else -> RetroArch
        }
    }
}
