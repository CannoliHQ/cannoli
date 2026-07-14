package dev.cannoli.igm

object IGMSettings {
    const val VIDEO = 0
    const val EMULATOR = 1
    const val INPUT = 2
    const val ADVANCED = 3
    const val INFO = 4

    val CATEGORIES = listOf("Video", "Emulator", "Input", "Advanced", "Info")

    object Input {
        const val BUTTON_MAPPINGS = 0
        const val SHORTCUTS = 1
        const val LEFT_STICK_DPAD = 2

        // Gated behind the Experimental Features setting, so the row count is derived from the
        // built list rather than a constant. DPAD_MODE is unreachable when the row is absent.
        const val DPAD_MODE = 3
    }
}
