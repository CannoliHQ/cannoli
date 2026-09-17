package dev.cannoli.igm

import androidx.annotation.StringRes
import dev.cannoli.ui.R as UIR

/**
 * A RetroPad button, in the order the remap screen lists them.
 *
 * [id] and [raKey] are RetroArch's own device id and key name, so a stored remap stays in one
 * vocabulary rather than inventing a second. [position] is where the button sits on a pad, which is
 * what a row is labelled with: the four face positions are named by the pad's own style, so they
 * carry no [labelRes].
 */
enum class RemapButton(
    val id: Int,
    val raKey: String,
    val position: CanonicalButton,
    @StringRes val labelRes: Int?,
) {
    UP(4, "up", CanonicalButton.BTN_UP, UIR.string.igm_button_up),
    DOWN(5, "down", CanonicalButton.BTN_DOWN, UIR.string.igm_button_down),
    LEFT(6, "left", CanonicalButton.BTN_LEFT, UIR.string.igm_button_left),
    RIGHT(7, "right", CanonicalButton.BTN_RIGHT, UIR.string.igm_button_right),
    SOUTH(0, "b", CanonicalButton.BTN_SOUTH, null),
    EAST(8, "a", CanonicalButton.BTN_EAST, null),
    WEST(1, "y", CanonicalButton.BTN_WEST, null),
    NORTH(9, "x", CanonicalButton.BTN_NORTH, null),
    L(10, "l", CanonicalButton.BTN_L, UIR.string.igm_button_l),
    R(11, "r", CanonicalButton.BTN_R, UIR.string.igm_button_r),
    L2(12, "l2", CanonicalButton.BTN_L2, UIR.string.canonical_l2),
    R2(13, "r2", CanonicalButton.BTN_R2, UIR.string.canonical_r2),
    L3(14, "l3", CanonicalButton.BTN_L3, UIR.string.canonical_l3),
    R3(15, "r3", CanonicalButton.BTN_R3, UIR.string.canonical_r3),
    START(3, "start", CanonicalButton.BTN_START, UIR.string.canonical_start),
    SELECT(2, "select", CanonicalButton.BTN_SELECT, UIR.string.canonical_select);

    companion object {
        fun forId(id: Int): RemapButton? = entries.firstOrNull { it.id == id }

        fun forPosition(position: CanonicalButton): RemapButton? =
            entries.firstOrNull { it.position == position }
    }
}

/**
 * Where a remap is stored and what its values mean.
 *
 * A remap map is keyed by RetroArch button id and holds the id that button sends. A button the map
 * does not mention sends itself, so an empty map is a pad that behaves normally.
 */
object ButtonRemap {
    /** What a cleared row sends, which is nothing. RetroArch's own file convention. */
    const val UNBOUND = -1

    /** Port argument meaning every port rather than one, for the native that applies a change. */
    const val ALL_PORTS = -1

    private const val KEY_PREFIX = "cannoli_remap_"

    fun keyFor(button: RemapButton): String = KEY_PREFIX + button.raKey

    fun buttonForKey(key: String): RemapButton? {
        if (!key.startsWith(KEY_PREFIX)) return null
        val raKey = key.removePrefix(KEY_PREFIX)
        return RemapButton.entries.firstOrNull { it.raKey == raKey }
    }

    /**
     * A stored value, or null when the text is not one.
     *
     * A tier is a text file someone can edit, and RetroArch indexes its bind array with whatever
     * this returns, so a number naming no button has to stop here.
     */
    fun valueOf(text: String?): Int? {
        val value = text?.trim()?.toIntOrNull() ?: return null
        return value.takeIf { it == UNBOUND || RemapButton.forId(it) != null }
    }

    fun target(map: Map<Int, Int>, button: RemapButton): Int = map[button.id] ?: button.id

    fun isDefault(map: Map<Int, Int>): Boolean =
        RemapButton.entries.all { target(map, it) == it.id }

    fun identity(): Map<Int, Int> = RemapButton.entries.associate { it.id to it.id }
}
