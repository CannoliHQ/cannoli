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

    Standalone(R.string.value_emulator_source_standalone);

    val emptyMessageRes: Int
        get() = when (this) {
            Embedded -> R.string.value_no_cores_found
            Standalone -> R.string.value_none_installed
        }

    companion object {
        fun fromRunnerLabel(label: String?): EmulatorSource? = when (label) {
            RETIRED_INTERNAL_SOURCE -> Embedded
            "Standalone", "App" -> Standalone
            null, "" -> null
            else -> Embedded
        }

        const val RETIRED_INTERNAL_SOURCE = "Internal"

        const val RETIRED_EXTERNAL_RA_SOURCE = "RetroArch"
    }
}
