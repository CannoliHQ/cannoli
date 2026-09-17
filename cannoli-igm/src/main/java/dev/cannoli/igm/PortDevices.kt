package dev.cannoli.igm

data class PortDeviceType(val id: Int, val label: String)

data class PortDevices(val current: Int, val types: List<PortDeviceType>) {
    // None silently kills that player's input, so it is never offered.
    val choices: List<PortDeviceType> get() = types.filter { it.id != RETRO_DEVICE_NONE }

    val hasChoice: Boolean get() = choices.size > 1

    fun labelFor(id: Int): String = types.firstOrNull { it.id == id }?.label ?: id.toString()

    companion object {
        const val RETRO_DEVICE_NONE = 0
        const val RETRO_DEVICE_JOYPAD = 1
        const val PLAYER_ROWS = 4
        private const val KEY_PREFIX = "input_libretro_device_p"

        fun keyFor(port: Int): String = "$KEY_PREFIX${port + 1}"

        fun portFor(key: String): Int? {
            if (!key.startsWith(KEY_PREFIX)) return null
            val port = key.removePrefix(KEY_PREFIX).toIntOrNull()?.minus(1) ?: return null
            return port.takeIf { it in 0 until PLAYER_ROWS }
        }
    }
}

data class PlayerSlot(val player: Int, val padIndex: Int, val name: String?, val setNumber: Int) {
    val hasPad: Boolean get() = name != null
}

// RetroArch never lets Player 1 end up without a pad, and the native swap refuses the same thing,
// so the menu never offers a swap it would refuse.
fun swapAllowed(slots: List<PlayerSlot>, a: Int, b: Int): Boolean {
    if (a == b) return false
    val first = slots.getOrNull(a) ?: return false
    val second = slots.getOrNull(b) ?: return false
    if (a == 0 && !second.hasPad) return false
    if (b == 0 && !first.hasPad) return false
    return true
}

fun List<PlayerSlot>.swapped(a: Int, b: Int): List<PlayerSlot> {
    val first = getOrNull(a) ?: return this
    val second = getOrNull(b) ?: return this
    return toMutableList().also {
        it[a] = second.copy(player = a)
        it[b] = first.copy(player = b)
    }
}
