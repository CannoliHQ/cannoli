package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.libretro.RetroJoypad

object CanonicalRetroMap {
    fun maskOf(button: CanonicalButton): Int = when (button) {
        CanonicalButton.BTN_SOUTH -> RetroJoypad.RETRO_B
        CanonicalButton.BTN_EAST -> RetroJoypad.RETRO_A
        CanonicalButton.BTN_WEST -> RetroJoypad.RETRO_Y
        CanonicalButton.BTN_NORTH -> RetroJoypad.RETRO_X
        CanonicalButton.BTN_L -> RetroJoypad.RETRO_L
        CanonicalButton.BTN_R -> RetroJoypad.RETRO_R
        CanonicalButton.BTN_L2 -> RetroJoypad.RETRO_L2
        CanonicalButton.BTN_R2 -> RetroJoypad.RETRO_R2
        CanonicalButton.BTN_L3 -> RetroJoypad.RETRO_L3
        CanonicalButton.BTN_R3 -> RetroJoypad.RETRO_R3
        CanonicalButton.BTN_START -> RetroJoypad.RETRO_START
        CanonicalButton.BTN_SELECT -> RetroJoypad.RETRO_SELECT
        CanonicalButton.BTN_UP -> RetroJoypad.RETRO_UP
        CanonicalButton.BTN_DOWN -> RetroJoypad.RETRO_DOWN
        CanonicalButton.BTN_LEFT -> RetroJoypad.RETRO_LEFT
        CanonicalButton.BTN_RIGHT -> RetroJoypad.RETRO_RIGHT
        CanonicalButton.BTN_MENU -> 0
    }
}
