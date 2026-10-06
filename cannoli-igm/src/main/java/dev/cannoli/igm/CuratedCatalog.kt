package dev.cannoli.igm

object RaKeys {
    const val ASPECT_RATIO_INDEX = "aspect_ratio_index"
    const val VIDEO_SCALE_INTEGER = "video_scale_integer"
}

object CuratedCatalog {

    data class Preset(val labelKey: String, val values: Map<String, String>)

    data class Row(val key: String, val presets: List<Preset>) {
        val settingKeys: Set<String> = presets.flatMap { it.values.keys }.toSet()

        val discriminatingKeys: Set<String> = settingKeys.filter { key ->
            presets.map { it.values[key] }.distinct().size > 1
        }.toSet()
    }

    data class Category(val key: String, val rows: List<Row>)

    val categories = listOf(
        Category(CATEGORY_VIDEO, listOf(
            Row("curated_screen_scaling", listOf(
                Preset("scaling_core_reported", mapOf(
                    RaKeys.ASPECT_RATIO_INDEX to "22",
                    RaKeys.VIDEO_SCALE_INTEGER to "false",
                )),
                Preset("scaling_integer", mapOf(
                    RaKeys.ASPECT_RATIO_INDEX to "22",
                    RaKeys.VIDEO_SCALE_INTEGER to "true",
                )),
                Preset("scaling_fullscreen", mapOf(
                    RaKeys.ASPECT_RATIO_INDEX to "24",
                    RaKeys.VIDEO_SCALE_INTEGER to "false",
                )),
            )),
            Row("curated_screen_sharpness", listOf(
                Preset("sharpness_sharp", mapOf("video_smooth" to "false")),
                Preset("sharpness_soft", mapOf("video_smooth" to "true")),
            )),
        )),
        Category(CATEGORY_ADVANCED, listOf(
            Row("curated_max_ff_speed", listOf(
                Preset("ff_2x", mapOf("fastforward_ratio" to "2")),
                Preset("ff_4x", mapOf("fastforward_ratio" to "4")),
                Preset("ff_8x", mapOf("fastforward_ratio" to "8")),
                Preset("ff_unlimited", mapOf("fastforward_ratio" to "0")),
            )),
            Row("curated_ff_mute", listOf(
                Preset("off", mapOf("audio_fastforward_mute" to "false")),
                Preset("on", mapOf("audio_fastforward_mute" to "true")),
            )),
            Row("curated_rewind", listOf(
                Preset("off", mapOf("rewind_enable" to "false")),
                Preset("on", mapOf("rewind_enable" to "true")),
            )),
            Row("curated_show_fps", listOf(
                Preset("off", mapOf("fps_show" to "false")),
                Preset("on", mapOf("fps_show" to "true")),
            )),
            Row("curated_debug_hud", listOf(
                Preset("off", mapOf(
                    "statistics_show" to "false",
                    "memory_show" to "false",
                    "framecount_show" to "false",
                )),
                Preset("on", mapOf(
                    "statistics_show" to "true",
                    "memory_show" to "true",
                    "framecount_show" to "true",
                )),
            )),
        )),
    )

    fun rowFor(itemKey: String): Row? =
        categories.firstOrNull { cat -> cat.rows.any { it.key == itemKey } }
            ?.rows?.first { it.key == itemKey }

    fun resolve(row: Row, current: Map<String, String>): Preset? =
        row.presets.firstOrNull { preset ->
            preset.values.all { (key, want) ->
                !current.containsKey(key) || sameValue(current[key], want)
            }
        }

    fun nextPreset(row: Row, current: Map<String, String>, direction: Int): Preset {
        val i = row.presets.indexOf(resolve(row, current))
        if (i < 0) return row.presets.first()
        return row.presets[(i + direction).mod(row.presets.size)]
    }

    private fun sameValue(have: String?, want: String): Boolean {
        if (have == null) return false
        if (have == want) return true
        val a = have.toDoubleOrNull() ?: return false
        val b = want.toDoubleOrNull() ?: return false
        return a == b
    }

    const val CATEGORY_VIDEO = "video"
    const val CATEGORY_ADVANCED = "advanced"
    const val CATEGORY_EMULATOR = "emulator"
    const val CATEGORY_INFO = "info"

    const val CATEGORY_OVERLAY = "overlay"

    const val CATEGORY_SHADER = "shader"

    const val CATEGORY_INPUT = "input"
    const val INPUT_SHORTCUTS = "shortcuts"
    const val INPUT_BUTTONS = "buttons"
}
