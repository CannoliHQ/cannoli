package dev.cannoli.scorza.config

import dev.cannoli.scorza.R

enum class EmulatorSource(val displayName: String) {
    /** The RetroArch built into this APK. Always present, and the only runner with the IGM. */
    Embedded("Embedded"),

    /** A separately installed RetroArch. The choice names which package. */
    RetroArch("RetroArch"),

    /** A separate emulator app. The choice names which package. */
    Standalone("Standalone");

    val emptyMessageRes: Int
        get() = when (this) {
            Embedded, RetroArch -> R.string.value_no_cores_found
            Standalone -> R.string.value_none_installed
        }

    companion object {
        /**
         * Migration only. v1 cores.json stored the picker's display caption as the runner, so a
         * source has to be recovered from it. Nothing at runtime may parse a caption back into a
         * source: options and choices carry [EmulatorSource] directly.
         *
         * "Internal" named the built-in libretro runner, which the embedded RetroArch replaced.
         * Any other core label becomes [RetroArch]; whether it resolves to [Embedded] depends on
         * the package the user had configured, which only the caller knows.
         */
        fun fromRunnerLabel(label: String?): EmulatorSource? = when (label) {
            RETIRED_INTERNAL_SOURCE -> Embedded
            "Standalone", "App" -> Standalone
            null, "" -> null
            else -> RetroArch
        }

        /** The source name v1 and early v2 files used for the removed built-in libretro runner. */
        const val RETIRED_INTERNAL_SOURCE = "Internal"
    }
}
