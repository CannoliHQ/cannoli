package dev.cannoli.scorza.libretro

private enum class Axis { HORIZONTAL, VERTICAL }

/**
 * Suppresses diagonals on the final libretro bitmask, after the per-game remap has been applied, so
 * every source of a direction is covered: hat D-pad, keycode D-pad, stick-as-D-pad, and a face
 * button remapped onto a direction.
 *
 * The axis already held keeps the core, and the perpendicular one is ignored until it releases.
 *
 * Same-axis opposition (UP+DOWN, LEFT+RIGHT) is not a diagonal and is left for the core to decide.
 */
class DiagonalLock {

    private val latched = mutableMapOf<Int, Axis>()

    fun filter(port: Int, mask: Int, allowDiagonals: Boolean): Int {
        if (allowDiagonals) return mask
        val h = mask and (RetroJoypad.RETRO_LEFT or RetroJoypad.RETRO_RIGHT)
        val v = mask and (RetroJoypad.RETRO_UP or RetroJoypad.RETRO_DOWN)
        if (h == 0 || v == 0) {
            when {
                h != 0 -> latched[port] = Axis.HORIZONTAL
                v != 0 -> latched[port] = Axis.VERTICAL
                else -> latched.remove(port)
            }
            return mask
        }
        // The latch is only written on the branch above, so it stays frozen for as long as a
        // diagonal is held. That is what stops the perpendicular axis from stealing it.
        return when (latched[port]) {
            Axis.HORIZONTAL -> mask and v.inv()
            Axis.VERTICAL -> mask and h.inv()
            // Both axes arrived with nothing latched: a hat D-pad snapped to a diagonal from rest.
            // There is no magnitude to compare and no press order, so emit neither rather than
            // guess wrong.
            null -> mask and h.inv() and v.inv()
        }
    }

    fun reset() {
        latched.clear()
    }
}
