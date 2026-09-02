package dev.cannoli.scorza.ui.viewmodel

/** Identity for a settings category. Sub-lists count: they are what `activeCategory` holds. */
enum class SettingsCategory {
    GENERAL,
    DISPLAY,
    LIBRARY,
    INPUT,
    EMULATION,
    INTEGRATIONS,
    ADVANCED,
    DEBUG,
    COLORS,
    STATUS_BAR,
    SCREEN_GEOMETRY,
    FGH_COLLECTION_PICKER,
    START_ON_PICKER,
    RETROACHIEVEMENTS,
    ROMM,
}

/** Identity for a settings row, not its label. */
enum class SettingsKey(val id: String) {
    LANGUAGE("language"),
    TITLE("title"),
    SWAP_PLAY_RESUME("swap_play_resume"),
    MAIN_MENU_QUIT("main_menu_quit"),
    SET_DEFAULT_LAUNCHER("set_default_launcher"),

    COLORS("colors"),
    BG_IMAGE("bg_image"),
    BG_TINT("bg_tint"),
    FONT("font"),
    TEXT_SIZE("text_size"),
    ART_SCALE("art_scale"),
    ART_WIDTH("art_width"),
    STATUS_BAR("status_bar"),
    PORTRAIT_MARGIN("portrait_margin"),

    CONTENT_MODE("content_mode"),
    FGH_COLLECTION("fgh_collection"),
    START_ON_PLATFORM("start_on_platform"),
    SHOW_RECENTLY_PLAYED("show_recently_played"),
    SHOW_FAVORITES("show_favorites"),
    MANAGE_PORTS("manage_ports"),
    MANAGE_TOOLS("manage_tools"),
    SCAN_LIBRARY("scan_library"),
    SD_ROOT("sd_root"),
    ROM_DIRECTORY("rom_directory"),

    COLOR_BACKGROUND("color_background"),
    COLOR_TEXT("color_text"),
    COLOR_STATUS_BAR("color_status_bar"),
    COLOR_HIGHLIGHT("color_highlight"),
    COLOR_HIGHLIGHT_TEXT("color_highlight_text"),
    COLOR_ACCENT("color_accent"),
    COLOR_TITLE("color_title"),

    SHOW_BATTERY("show_battery"),
    SHOW_BLUETOOTH("show_bluetooth"),
    SHOW_CLOCK("show_clock"),
    SHOW_KITCHEN("show_kitchen"),
    SHOW_DOWNLOADS("show_downloads"),
    SHOW_UPDATE("show_update"),
    SHOW_VPN("show_vpn"),
    SHOW_WIFI("show_wifi"),

    SCREEN_GEO_WIDTH("screen_geo_width"),
    SCREEN_GEO_HEIGHT("screen_geo_height"),
    SCREEN_GEO_X("screen_geo_x"),
    SCREEN_GEO_Y("screen_geo_y"),

    CONTROLLERS("controllers"),
    SHORTCUTS("shortcuts"),
    INPUT_TESTER("input_tester"),

    CORE_MAPPING("core_mapping"),
    UPDATE_CORES("update_cores"),
    UPDATE_SHADERS("update_shaders"),
    INSTALLED_CORES("installed_cores"),
    ALWAYS_SAVE_ON_QUIT("always_save_on_quit"),
    IGM_SETTINGS_MODE("igm_settings_mode"),
    DEFAULT_VIDEO_DRIVER("default_video_driver"),

    INTEGRATIONS_RA("integrations_ra"),
    INTEGRATIONS_ROMM("integrations_romm"),

    RA_USERNAME("ra_username"),
    RA_PASSWORD("ra_password"),
    RA_LOGIN("ra_login"),

    ROMM_HOST("romm_host"),
    ROMM_ALLOW_SELF_SIGNED("romm_allow_self_signed"),
    ROMM_PAIR("romm_pair"),
    ROMM_PAIR_CODE("romm_pair_code"),

    LOGGING("logging"),
    SCREEN_GEOMETRY("screen_geometry"),
    PERMISSIONS("permissions"),
    REGENERATE_SYSTEM_FOLDERS("regenerate_system_folders"),
    RESET_CUSTOM_CONFIG("reset_custom_config"),
    KITCHEN_CODE_BYPASS("kitchen_code_bypass"),
    RELEASE_CHANNEL("release_channel"),

    AUDIT_EMULATOR_INTENTS("audit_emulator_intents"),
    ICON_GALLERY("icon_gallery"),
    DEVELOPER_OPTIONS("developer_options"),

    // Throwaway, with the Wi-Fi Direct netplay spike. Remove with it.
    WIFI_DIRECT_PROBE("wifi_direct_probe"),
    WIFI_DIRECT_HOST("wifi_direct_host");

    companion object {
        /** One row per pickable collection, so the id rides in the key. */
        const val FGH_PICK_PREFIX = "fgh_pick:"
        const val START_ON_PICK_PREFIX = "start_on_pick:"

        val COLOR_ROWS = setOf(
            COLOR_BACKGROUND, COLOR_TEXT, COLOR_STATUS_BAR, COLOR_HIGHLIGHT,
            COLOR_HIGHLIGHT_TEXT, COLOR_ACCENT, COLOR_TITLE,
        )
        val SCREEN_GEOMETRY_ROWS = setOf(SCREEN_GEO_WIDTH, SCREEN_GEO_HEIGHT, SCREEN_GEO_X, SCREEN_GEO_Y)

        fun fromId(id: String?): SettingsKey? = entries.firstOrNull { it.id == id }
    }
}
