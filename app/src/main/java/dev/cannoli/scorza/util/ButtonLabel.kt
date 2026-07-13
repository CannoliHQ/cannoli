package dev.cannoli.scorza.util

import android.content.Context
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.InputBinding

fun canonicalLabel(context: Context, button: CanonicalButton, style: GlyphStyle): String {
    val face = when (button) {
        CanonicalButton.BTN_SOUTH -> R.string.canonical_south to when (style) {
            GlyphStyle.PLUMBER -> R.string.glyph_plumber_south
            GlyphStyle.REDMOND -> R.string.glyph_redmond_south
            GlyphStyle.SHAPES -> R.string.glyph_shapes_south
        }
        CanonicalButton.BTN_EAST -> R.string.canonical_east to when (style) {
            GlyphStyle.PLUMBER -> R.string.glyph_plumber_east
            GlyphStyle.REDMOND -> R.string.glyph_redmond_east
            GlyphStyle.SHAPES -> R.string.glyph_shapes_east
        }
        CanonicalButton.BTN_WEST -> R.string.canonical_west to when (style) {
            GlyphStyle.PLUMBER -> R.string.glyph_plumber_west
            GlyphStyle.REDMOND -> R.string.glyph_redmond_west
            GlyphStyle.SHAPES -> R.string.glyph_shapes_west
        }
        CanonicalButton.BTN_NORTH -> R.string.canonical_north to when (style) {
            GlyphStyle.PLUMBER -> R.string.glyph_plumber_north
            GlyphStyle.REDMOND -> R.string.glyph_redmond_north
            GlyphStyle.SHAPES -> R.string.glyph_shapes_north
        }
        else -> null
    }
    if (face != null) {
        val (cardinalRes, glyphRes) = face
        return context.getString(
            R.string.canonical_face_with_glyph,
            context.getString(cardinalRes),
            context.getString(glyphRes),
        )
    }
    val res = when (button) {
        CanonicalButton.BTN_UP -> R.string.canonical_dpad_up
        CanonicalButton.BTN_DOWN -> R.string.canonical_dpad_down
        CanonicalButton.BTN_LEFT -> R.string.canonical_dpad_left
        CanonicalButton.BTN_RIGHT -> R.string.canonical_dpad_right
        CanonicalButton.BTN_L -> R.string.canonical_l1
        CanonicalButton.BTN_R -> R.string.canonical_r1
        CanonicalButton.BTN_L2 -> R.string.canonical_l2
        CanonicalButton.BTN_R2 -> R.string.canonical_r2
        CanonicalButton.BTN_L3 -> R.string.canonical_l3
        CanonicalButton.BTN_R3 -> R.string.canonical_r3
        CanonicalButton.BTN_START -> R.string.canonical_start
        CanonicalButton.BTN_SELECT -> R.string.canonical_select
        CanonicalButton.BTN_MENU -> R.string.canonical_menu
        else -> return button.name
    }
    return context.getString(res)
}

/**
 * Shortcut chords store the physical keycode the pad reported. Name it after the canonical button
 * the active mapping assigns to that key, so someone who has remapped (say, swapped Start and
 * Select) reads back the button they configured rather than the one the hardware ships with.
 * Keys the mapping does not bind as a button (hat directions, analog triggers) keep their
 * hardware name.
 */
fun buttonLabel(context: Context, keyCode: Int, mapping: DeviceMapping?, style: GlyphStyle): String {
    val canonical = canonicalForKeyCode(keyCode, mapping)
    return canonical?.let { canonicalLabel(context, it, style) } ?: keyCodeName(keyCode)
}

fun canonicalForKeyCode(keyCode: Int, mapping: DeviceMapping?): CanonicalButton? =
    mapping?.bindings?.entries?.firstOrNull { (_, bindings) ->
        bindings.any { it is InputBinding.Button && it.keyCode == keyCode }
    }?.key
