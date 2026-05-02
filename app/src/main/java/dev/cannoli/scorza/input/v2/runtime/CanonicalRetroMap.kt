package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.libretro.LibretroInput

object CanonicalRetroMap {
    fun maskOf(button: CanonicalButton): Int = when (button) {
        CanonicalButton.BTN_SOUTH -> LibretroInput.RETRO_B
        CanonicalButton.BTN_EAST -> LibretroInput.RETRO_A
        CanonicalButton.BTN_WEST -> LibretroInput.RETRO_Y
        CanonicalButton.BTN_NORTH -> LibretroInput.RETRO_X
        CanonicalButton.BTN_L -> LibretroInput.RETRO_L
        CanonicalButton.BTN_R -> LibretroInput.RETRO_R
        CanonicalButton.BTN_L2 -> LibretroInput.RETRO_L2
        CanonicalButton.BTN_R2 -> LibretroInput.RETRO_R2
        CanonicalButton.BTN_L3 -> LibretroInput.RETRO_L3
        CanonicalButton.BTN_R3 -> LibretroInput.RETRO_R3
        CanonicalButton.BTN_START -> LibretroInput.RETRO_START
        CanonicalButton.BTN_SELECT -> LibretroInput.RETRO_SELECT
        CanonicalButton.BTN_UP -> LibretroInput.RETRO_UP
        CanonicalButton.BTN_DOWN -> LibretroInput.RETRO_DOWN
        CanonicalButton.BTN_LEFT -> LibretroInput.RETRO_LEFT
        CanonicalButton.BTN_RIGHT -> LibretroInput.RETRO_RIGHT
        CanonicalButton.BTN_MENU -> 0
    }
}
