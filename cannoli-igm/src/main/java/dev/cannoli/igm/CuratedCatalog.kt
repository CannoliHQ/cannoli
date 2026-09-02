package dev.cannoli.igm

// The curated in-game settings: task-shaped rows rather than key-shaped ones, so "Screen Scaling"
// is one choice instead of three RetroArch settings the player has to combine correctly.
//
// Values here are RetroArch's MACHINE values, matched against RaSetting.machineValue, never against
// RaSetting.value. A combobox like aspect_ratio_index reports "Core Provided" in value, which is
// translated and therefore not something to compare.
//
// Aspect indices come from enum aspect_ratio in retroarch/gfx/video_defines.h:
// ASPECT_RATIO_CORE = 22, ASPECT_RATIO_CUSTOM = 23, ASPECT_RATIO_FULL = 24.
/**
 * RetroArch keys that more than one part of Cannoli names.
 *
 * The viewport takeover shadows these two while it holds the screen, and the curated catalogue
 * matches against the shadowed pair to resolve a scaling preset. A spelling that drifts between the
 * two ends resolves no preset, and the row then adopts the first one over the user's choice.
 */
object RaKeys {
    const val ASPECT_RATIO_INDEX = "aspect_ratio_index"
    const val VIDEO_SCALE_INTEGER = "video_scale_integer"
}

object CuratedCatalog {

    data class Preset(val labelKey: String, val values: Map<String, String>)

    data class Row(val key: String, val presets: List<Preset>) {
        val settingKeys: Set<String> = presets.flatMap { it.values.keys }.toSet()

        // Keys whose value differs between presets, so they are what a choice actually means. The
        // rest are normalization: they hold one value across every preset and exist only to stop a
        // previous preset leaving something behind. RetroArch does not register every key on every
        // build (ricotta_ra_find returns null for a setting with no short_description), so a row is
        // usable as long as its discriminating keys resolve. Requiring all of them would delete a
        // whole row over a key that never changes.
        val discriminatingKeys: Set<String> = settingKeys.filter { key ->
            presets.map { it.values[key] }.distinct().size > 1
        }.toSet()
    }

    data class Category(val key: String, val rows: List<Row>)

    val categories = listOf(
        Category(CATEGORY_VIDEO, listOf(
            // v1's vocabulary and order, from LibretroActivity.scalingLabel() at 13ea3287^. v1 also
            // had Integer Overscale and Aspect Screen; the former needs
            // video_scale_integer_overscale, which RetroArch does not register on this build (it
            // sat in every preset here doing nothing until the census guard caught it), and the
            // latter was a viewport mode of Cannoli's own renderer with no RetroArch equivalent.
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
            // 0 is unlimited in RetroArch, so it reads as a speed rather than as "off".
            Row("curated_max_ff_speed", listOf(
                Preset("ff_2x", mapOf("fastforward_ratio" to "2")),
                Preset("ff_4x", mapOf("fastforward_ratio" to "4")),
                Preset("ff_8x", mapOf("fastforward_ratio" to "8")),
                Preset("ff_unlimited", mapOf("fastforward_ratio" to "0")),
            )),
            // Off by default in RetroArch, and worth leaving that way per platform: the buffer is
            // real memory and the cost of filling it is paid on every frame, which a heavier system
            // feels and a handheld one does not.
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

    // Keys absent from `current` are ones RetroArch does not expose here, so they cannot disagree.
    // A preset still has to match on everything that is present, discriminating or not.
    fun resolve(row: Row, current: Map<String, String>): Preset? =
        row.presets.firstOrNull { preset ->
            preset.values.all { (key, want) ->
                !current.containsKey(key) || sameValue(current[key], want)
            }
        }

    // Custom has no position in the preset list, so cycling off it goes to the first preset rather
    // than to a neighbour of an index that does not exist.
    fun nextPreset(row: Row, current: Map<String, String>, direction: Int): Preset {
        val i = row.presets.indexOf(resolve(row, current))
        if (i < 0) return row.presets.first()
        return row.presets[(i + direction).mod(row.presets.size)]
    }

    // RetroArch renders floats with %g, so 2.0 arrives as "2" and a preset written either way has
    // to match. Anything that is not a number, booleans included, compares exactly.
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

    // Cannoli's own, not a RetroArch settings screen. Like CATEGORY_INFO it belongs to both
    // menus, and like it the provider answers the key before the curated/all branch.
    const val CATEGORY_OVERLAY = "overlay"

    // Also Cannoli's own, and a tree rather than a screen: the database is thousands of presets
    // in folders, so the rows below it are browsed with the same path stack every category uses.
    const val CATEGORY_SHADER = "shader"

    // Cannoli's own, like the overlay and shader categories: a settings row whose entry hands off
    // to a screen of Cannoli's rather than to a list of RetroArch settings.
    const val CATEGORY_INPUT = "input"
    const val INPUT_SHORTCUTS = "shortcuts"
}
