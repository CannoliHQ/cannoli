package dev.cannoli.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.roundToLong
import dev.cannoli.ui.R

val Black = Color(0xFF000000)
val White = Color(0xFFFFFFFF)
val GrayText = Color(0xFF999999)
val DarkGray = Color(0xFF1A1A1A)
val ProgressTrack = Color(0xFF333333)
val SurfaceDim = Color(0xFF1A1A1E)
val PolaroidDark = Color(0xFF222222)
val PolaroidSelect = Color(0xFF4A90D9)
val PolaroidInactive = Color(0xFFCCCCCC)
val Success = Color(0xFF90EE90)
val ErrorText = Color(0xFFFF6B6B)
val ErrorHighlight = Color(0xFFFF5555)
val Warning = Color(0xFFF5C400)

data class CannoliColors(
    val highlight: Color = Color.White,
    val text: Color = Color.White,
    val highlightText: Color = Color.Black,
    val accent: Color = Color.White,
    val title: Color = Color.White,
    val background: Color = Color.Black,
    val statusBar: Color = Color.White
)

val LocalCannoliColors = staticCompositionLocalOf { CannoliColors() }
val LocalCannoliFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }
val LocalCannoliIconFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }
val LocalScaleFactor = staticCompositionLocalOf { 1f }

// Pill geometry scales down with the text size but never up, so sizes below the default
// keep the default's proportions instead of drowning small text in fixed padding.
val LocalPillScale = staticCompositionLocalOf { 1f }

/** [nameRes] rather than a name: the picker draws it, so it has to be translatable. */
data class ColorPreset(@androidx.annotation.StringRes val nameRes: Int, val color: Long)

val COLOR_PRESETS = listOf(
    ColorPreset(R.string.color_black, 0xFF000000),
    ColorPreset(R.string.color_dark_grey, 0xFF3A3A3C),
    ColorPreset(R.string.color_light_grey, 0xFFC0BFBE),
    ColorPreset(R.string.color_white, 0xFFFFFFFF),
    ColorPreset(R.string.color_flame_red, 0xFFCC1A1A),
    ColorPreset(R.string.color_crimson, 0xFFB8002A),
    ColorPreset(R.string.color_berry, 0xFFC0336B),
    ColorPreset(R.string.color_coral, 0xFFE8604A),
    ColorPreset(R.string.color_spice, 0xFFE86A10),
    ColorPreset(R.string.color_dandelion, 0xFFF5C400),
    ColorPreset(R.string.color_kiwi, 0xFF5AB820),
    ColorPreset(R.string.color_teal, 0xFF00897B),
    ColorPreset(R.string.color_neon_blue, 0xFF0AB9E6),
    ColorPreset(R.string.color_indigo, 0xFF3D4DB5),
    ColorPreset(R.string.color_grape, 0xFF7B3FA0),
    ColorPreset(R.string.color_midnight_purple, 0xFF4A1A6E)
)

fun hexToColor(hex: String): Color? {
    val clean = hex.removePrefix("#")
    if (clean.length != 6) return null
    return try {
        Color(0xFF000000 or clean.toLong(16))
    } catch (_: NumberFormatException) {
        null
    }
}

/** For the hosts that carry the theme as hex strings, where an absent or unparseable one is the default. */
fun cannoliColorsFromHex(
    highlight: String?,
    text: String?,
    highlightText: String?,
    accent: String?,
    title: String?,
): CannoliColors {
    val defaults = CannoliColors()
    fun parse(hex: String?, fallback: Color) = hex?.let { hexToColor(it) } ?: fallback
    return CannoliColors(
        highlight = parse(highlight, defaults.highlight),
        text = parse(text, defaults.text),
        highlightText = parse(highlightText, defaults.highlightText),
        accent = parse(accent, defaults.accent),
        title = parse(title, defaults.title),
    )
}

fun colorToArgbLong(color: Color): Long {
    val r = (color.red * 255).roundToLong()
    val g = (color.green * 255).roundToLong()
    val b = (color.blue * 255).roundToLong()
    return (0xFFL shl 24) or (r shl 16) or (g shl 8) or b
}
