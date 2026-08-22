package dev.cannoli.igm

data class RaOptionStrings(
    val rootTitle: String = "Settings",
    val on: String = "On",
    val off: String = "Off",
    val restartHint: String = "Applies On Relaunch",
    val savePlatform: String = "Save for Platform",
    val saveGame: String = "Save for this game",
    val dontSave: String = "Discard",
    val nativeMenu: String = "RetroArch Menu",
    val categoryTitles: Map<String, String> = mapOf(
        "video" to "Video",
        "audio" to "Audio",
        "latency" to "Latency",
        "speed" to "Speed & Rewind",
        "osd" to "On-Screen Display",
    ),
    // Labels for host-local toggles (keys prefixed "cannoli_") shown in a category.
    val localToggleLabels: Map<String, String> = mapOf(
        "cannoli_osd_reset" to "Reset OSD",
    ),
    // Shown when the live values match no preset of a curated row, which happens after editing the
    // same settings individually in the Everything menu.
    // Keyed "<category>/<subcategory>", so Video and Audio can both have an Output screen.
    val subcategoryTitles: Map<String, String> = emptyMap(),
    val custom: String = "Custom",
    val infoCore: String = "Core",
    val infoCoreVersion: String = "Core Version",
    val curatedCategoryTitles: Map<String, String> = mapOf(
        CuratedCatalog.CATEGORY_VIDEO to "Video",
        CuratedCatalog.CATEGORY_EMULATOR to "Emulator",
        CuratedCatalog.CATEGORY_ADVANCED to "Advanced",
        CuratedCatalog.CATEGORY_INFO to "Info",
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
