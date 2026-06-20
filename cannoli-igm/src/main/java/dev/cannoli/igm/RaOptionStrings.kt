package dev.cannoli.igm

data class RaOptionStrings(
    val on: String = "On",
    val off: String = "Off",
    val restartHint: String = "Applies on relaunch",
    val savePlatform: String = "Save for Platform",
    val saveGame: String = "Save for this game",
    val dontSave: String = "Discard",
    val nativeMenu: String = "RetroArch Menu",
    val categoryTitles: Map<String, String> = mapOf(
        "video" to "Video",
        "audio" to "Audio",
        "latency" to "Latency",
        "notifications" to "Notifications",
    ),
)
