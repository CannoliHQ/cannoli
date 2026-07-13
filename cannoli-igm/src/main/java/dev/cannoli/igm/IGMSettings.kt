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

        const val COUNT = 3
    }
}
