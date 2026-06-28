package dev.cannoli.igm

object RaOptionCatalog {

    data class Category(val key: String, val settingKeys: List<String>)

    val categories = listOf(
        Category("video", listOf(
            "aspect_ratio_index",
            "video_scale_integer",
            "video_smooth",
            "video_rotation",
            "video_vsync",
            "video_black_frame_insertion",
        )),
        Category("audio", listOf(
            "audio_enable",
            "audio_mute_enable",
            "audio_volume",
            "audio_latency",
            "audio_rate_control_delta",
        )),
        Category("latency", listOf(
            "run_ahead_enabled",
            "run_ahead_frames",
            "run_ahead_secondary_instance",
            "video_frame_delay",
            "video_hard_sync",
            "video_hard_sync_frames",
            "video_swap_interval",
            "audio_sync",
        )),
        Category("osd", listOf(
            // Cannoli OSD events (RA-key-backed; reset is a host-local toggle)
            "notification_show_save_state",
            "cannoli_osd_reset",
            "notification_show_fast_forward",
            "notification_show_disk_control",
            "notification_show_screenshot",
            "notification_show_autoconfig",
            // Performance HUD
            "fps_show",
            "statistics_show",
            "memory_show",
        )),
    )
}
