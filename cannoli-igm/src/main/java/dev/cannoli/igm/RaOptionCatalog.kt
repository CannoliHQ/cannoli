package dev.cannoli.igm

object RaOptionCatalog {

    data class Category(val key: String, val settingKeys: List<String>)

    val categories = listOf(
        Category("video", listOf(
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
        Category("input", listOf(
            "input_analog_deadzone",
            "input_analog_sensitivity",
            "input_axis_threshold",
            "input_turbo_period",
            "input_turbo_duty_cycle",
            "input_turbo_mode",
            "input_turbo_default_button",
            "input_auto_game_focus",
        )),
        Category("savestates", listOf(
            "cheevos_hardcore_mode_enable",
            "savestate_auto_save",
            "savestate_auto_load",
            "savestate_auto_index",
            "savestate_thumbnail_enable",
            "savestate_file_compression",
            "save_file_compression",
            "block_sram_overwrite",
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
