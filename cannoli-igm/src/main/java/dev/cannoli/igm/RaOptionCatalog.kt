package dev.cannoli.igm

object RaOptionCatalog {

    data class Category(val key: String, val settingKeys: List<String>)

    // RetroArch's Drivers menu (menu_displaylist.c, DISPLAYLIST_DRIVER_SETTINGS_LIST) holds twelve
    // keys and is not a category here. video_driver and audio_driver are promoted into Video and
    // Audio; the rest are excluded.
    //
    // menu_driver is pointless: Cannoli replaces RetroArch's menu, so switching it achieves nothing
    // and can strand a user in an interface Cannoli does not drive.
    //
    // input_driver and joypad_driver are NEVER exposed. Cannoli writes input_driver = "android"
    // into every controller cfg it generates, so changing either means no cfg matches any pad and
    // every controller silently loses its mapping in game. Do not add them back as an oversight.
    //
    // microphone, record, midi, bluetooth, wifi, camera and location are meaningless on this
    // platform.
    val categories = listOf(
        Category("video", listOf(
            // From the Drivers menu. gl / glcore / vulkan, and the right answer varies by device.
            "video_driver",
            "aspect_ratio_index",
            "video_scale_integer",
            "video_scale_integer_overscale",
            "video_smooth",
            "video_rotation",
            "video_crop_overscan",
            "video_vsync",
            "video_threaded",
            "video_black_frame_insertion",
            "video_bfi_dark_frames",
            "video_shader_subframes",
        )),
        Category("audio", listOf(
            // From the Drivers menu. Affects audio latency, which is a real tuning axis on Android.
            "audio_driver",
            "audio_enable",
            "audio_mute_enable",
            "audio_volume",
            "audio_mixer_volume",
            "audio_latency",
            "audio_resampler_quality",
            "audio_rate_control_delta",
            "audio_max_timing_skew",
            "audio_sync",
        )),
        Category("latency", listOf(
            "run_ahead_enabled",
            "run_ahead_frames",
            "run_ahead_secondary_instance",
            "run_ahead_hide_warnings",
            "video_frame_delay",
            "video_frame_delay_auto",
            "video_hard_sync",
            "video_hard_sync_frames",
            "video_swap_interval",
            "input_poll_type_behavior",
        )),
        Category("speed", listOf(
            "fastforward_ratio",
            "fastforward_frameskip",
            "slowmotion_ratio",
            "vrr_runloop_enable",
            "rewind_enable",
            "rewind_granularity",
            "rewind_buffer_size",
        )),
        Category("osd", listOf(
            // Performance HUD
            "video_font_enable",
            "video_font_size",
            "video_msg_bgcolor_enable",
            "menu_widgets_enable",
            "fps_show",
            "fps_update_interval",
            "framecount_show",
            "statistics_show",
            "memory_show",
            // Cannoli OSD event notifications
            "notification_show_save_state",
            "notification_show_fast_forward",
            "notification_show_screenshot",
            "notification_show_disk_control",
            "notification_show_autoconfig",
            "notification_show_remap_load",
            "notification_show_cheats_applied",
            "notification_show_patch_applied",
            // Host-local toggle (not an RA override)
            "cannoli_osd_reset",
        )),
    )
}
