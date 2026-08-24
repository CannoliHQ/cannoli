package dev.cannoli.scorza.config

import dev.cannoli.scorza.R

enum class EmulatorSource(val labelRes: Int) {
    /**
     * The RetroArch built into this APK. Always present, and the only runner with the IGM.
     *
     * Shown as "Internal", which is what v1 called its built-in runner and what testers still
     * recognise. The constant stays Embedded because cores.json stores `source.name`, and because
     * [RETIRED_INTERNAL_SOURCE] already maps the v1 caption onto this value.
     */
    Embedded(R.string.value_emulator_source_internal),

    /** A separately installed RetroArch. The choice names which package. */
    RetroArch(R.string.value_emulator_source_retroarch),

    /** A separate emulator app. The choice names which package. */
    Standalone(R.string.value_emulator_source_standalone);

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
         * Any other core label named a separately installed RetroArch, a tier that no longer
         * exists, so it resolves to the runner that can still load the core it chose.
         */
        fun fromRunnerLabel(label: String?): EmulatorSource? = when (label) {
            RETIRED_INTERNAL_SOURCE -> Embedded
            "Standalone", "App" -> Standalone
            null, "" -> null
            else -> Embedded
        }

        /** The source name v1 and early v2 files used for the removed built-in libretro runner. */
        const val RETIRED_INTERNAL_SOURCE = "Internal"

        /**
         * The source name used for a separately installed RetroArch, a tier that no longer exists.
         * Every stored occurrence names an external install, since the embedded runner was never
         * released, and none of them can run anything now.
         */
        const val RETIRED_EXTERNAL_RA_SOURCE = "RetroArch"
    }
}
