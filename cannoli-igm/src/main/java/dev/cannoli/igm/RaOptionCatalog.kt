package dev.cannoli.igm

object RaOptionCatalog {

    // Nesting is one level deep. RetroArch groups Video and Audio into submenus and All Settings
    // mirrors that, so an instruction from RetroArch's own documentation finds the setting where it
    // says it is. A subcategory's own subcategories are never rendered, so do not create any.
    data class Category(
        val key: String,
        val settingKeys: List<String>,
        val subcategories: List<Category> = emptyList(),
    )

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
        // Mirrors RetroArch's own Video screen: its direct entries, then its submenus. Three of its
        // seven submenus are dropped whole: CRT_SWITCHRES is CRT modeswitching, and
        // VIDEO_FULLSCREEN_MODE and VIDEO_WINDOWED_MODE are desktop window management.
        //
        // Excluded from the direct list: brightness_control, video_filter_remove and
        // video_notch_write_over are menu actions with no config key; video_dingux_* is another
        // platform; video_use_metal_arg_buffers is Metal; video_shader_delay belongs with the
        // shader work that has no bridge yet.
        Category("video", listOf(
            // From the Drivers menu. gl / glcore / vulkan, and the right answer varies by device.
            "video_driver",
            "video_filter_enable",
            "video_filter",
        ), subcategories = listOf(
            // Excluded: video_gpu_index, screen_resolution, pal60_enable, video_gamma,
            // video_soft_filter, video_filter_flicker and video_refresh_rate_polled/auto are menu
            // actions or readouts with no config key; video_monitor_index and video_window_offset_*
            // are desktop and multi-monitor; video_wiiu_prefer_drc and video_dingux_refresh_rate are
            // other platforms.
            Category("output", listOf(
                "video_threaded",
                "video_rotation",
                "screen_orientation",
                "video_refresh_rate",
                "video_autoswitch_refresh_rate",
                "video_autoswitch_pal_threshold",
                // Registered only on the gl driver, so absent on vulkan or glcore.
                "video_force_srgb_disable",
            )),
            // The menu calls it video_aspect_ratio_index; the config key is aspect_ratio_index.
            // Excluded: video_viewport_custom_* and video_vi_width have no config key;
            // video_dingux_ipu_keep_aspect is another platform.
            Category("scaling", listOf(
                "aspect_ratio_index",
                "video_aspect_ratio",
                "video_scale_integer",
                "video_scale_integer_axis",
                "video_scale_integer_scaling",
                "video_smooth",
                "video_crop_overscan",
                "video_viewport_bias_x",
                "video_viewport_bias_y",
                "video_viewport_bias_portrait_x",
                "video_viewport_bias_portrait_y",
            )),
            // RetroArch lists several of these under Latency as well. Cannoli keeps a key in one
            // place only, and the ones it already had under Latency stay there:
            // video_frame_delay, video_frame_delay_auto, video_hard_sync, video_hard_sync_frames
            // and video_swap_interval. vrr_runloop_enable likewise stays under Speed & Rewind.
            Category("synchronization", listOf(
                "video_vsync",
                // Registered only when the driver reports GFX_CTX_FLAGS_ADAPTIVE_VSYNC.
                "video_adaptive_vsync",
                "video_black_frame_insertion",
                "video_bfi_dark_frames",
                "video_shader_subframes",
                "video_waitable_swapchains",
                "video_max_frame_latency",
                "video_max_swapchain_images",
                "video_scanline_sync",
            )),
            // The menu calls the toggle video_hdr_enable; the config key is video_hdr_mode.
            // Registered only when the active video driver reports VIDEO_FLAG_HDR_SUPPORT, so this
            // whole screen is absent on a display that does not do HDR. That is the point of
            // omitting an empty subcategory: keep it, and hardware that supports it gets it.
            Category("hdr", listOf(
                "video_hdr_mode",
                "video_hdr_paper_white_nits",
                "video_hdr_expand_gamut",
                "video_hdr_scanlines",
                "video_hdr_subpixel_layout",
            )),
        )),
        // Mirrors RetroArch's Audio screen. Two of its five submenus are dropped whole:
        // MICROPHONE and MIDI, whose driver families are already excluded and which have no
        // hardware path here. AUDIO_MIXER is dropped for a different reason: its list is built from
        // live mixer streams rather than settings, so there is nothing to expose.
        //
        // Excluded from the direct list: menu_sounds is a submenu, audio_dsp_plugin_remove is an
        // action, and system_bgm_enable has no config key.
        // Mirrors RetroArch's Audio screen. Two of its five submenus are dropped whole:
        // MICROPHONE and MIDI, whose driver families are already excluded and which have no
        // hardware path here. AUDIO_MIXER is dropped for a different reason: its list is built from
        // live mixer streams rather than settings, so there is nothing to expose.
        //
        // Excluded from the direct list: menu_sounds is a submenu, audio_dsp_plugin_remove is an
        // action, system_bgm_enable has no config key, audio_respect_silent_mode is TARGET_OS_IOS,
        // and audio_rewind_mute has no menu registration.
        //
        // audio_driver sits here rather than under Output, where RetroArch puts it, because its
        // label is "Audio" and so is audio_enable's. On one screen that reads as two identical
        // rows; RetroArch never shows them together because its driver list is a separate screen.
        Category("audio", listOf(
            "audio_driver",
            "audio_volume",
            "audio_mute_enable",
            "audio_mixer_volume",
            "audio_mixer_mute_enable",
            "audio_fastforward_mute",
            "audio_fastforward_speedup",
            "audio_dsp_plugin",
        ), subcategories = listOf(
            // Excluded: audio_output_rate and audio_resampler_driver have no config key, the
            // wasapi trio and audio_asio_control_panel are Windows, and audio_device is a free-text
            // backend string with no picker on Android.
            Category("output", listOf(
                "audio_enable",
                "audio_latency",
                "audio_resampler_quality",
                "audio_block_frames",
            )),
            Category("synchronization", listOf(
                "audio_sync",
                "audio_max_timing_skew",
                "audio_rate_control_delta",
            )),
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
