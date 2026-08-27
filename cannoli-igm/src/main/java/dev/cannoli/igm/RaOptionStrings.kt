package dev.cannoli.igm

data class RaOptionStrings(
    val rootTitle: String = "Settings",
    val on: String = "On",
    val off: String = "Off",
    val restartHint: String = "Applies On Relaunch",
    val savePlatform: String = "Save for Platform",
    val saveGame: String = "Save for this game",
    val dontSave: String = "Discard",
    // The core options row. Every other All Settings title comes from RetroArch itself.
    val emulator: String = "Emulator",
    val custom: String = "Custom",
    val infoCore: String = "Core",
    val infoCoreVersion: String = "Core Version",
    val curatedCategoryTitles: Map<String, String> = mapOf(
        CuratedCatalog.CATEGORY_VIDEO to "Video",
        CuratedCatalog.CATEGORY_EMULATOR to "Emulator",
        CuratedCatalog.CATEGORY_ADVANCED to "Advanced",
        CuratedCatalog.CATEGORY_INFO to "Info",
        CuratedCatalog.CATEGORY_OVERLAY to "Overlay",
    ),
    val curatedRowLabels: Map<String, String> = mapOf(
        "curated_screen_scaling" to "Screen Scaling",
        "curated_screen_sharpness" to "Screen Sharpness",
        "curated_max_ff_speed" to "Max Fast-Forward Speed",
        "curated_show_fps" to "Show FPS",
        "curated_debug_hud" to "Debug HUD",
    ),
    val curatedPresetLabels: Map<String, String> = mapOf(
        "scaling_core_reported" to "Core Reported",
        "scaling_integer" to "Integer",
        "scaling_fullscreen" to "Fullscreen",
        "sharpness_sharp" to "Sharp",
        "sharpness_soft" to "Soft",
        "ff_2x" to "2x",
        "ff_4x" to "4x",
        "ff_8x" to "8x",
        "ff_unlimited" to "Unlimited",
        "on" to "On",
        "off" to "Off",
    ),
)
