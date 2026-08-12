package dev.cannoli.scorza.input.autoconfig

// RetroArch cfgs number input_*_axis values as indices into native RA's compacted 10-slot analog
// table, not as raw Android MotionEvent axis ids. Translate at the cfg boundary; the launcher uses
// raw Android axis ids everywhere internally. Unknown ids pass through unchanged.
object RaAxisSlots {
    private val slotToAndroid = mapOf(
        0 to 0, 1 to 1, 2 to 11, 3 to 14, 4 to 12, 5 to 13,
        6 to 17, 7 to 18, 8 to 23, 9 to 22,
    )
    private val androidToSlot = slotToAndroid.entries.associate { (slot, android) -> android to slot }

    fun toAndroidAxis(slot: Int): Int = slotToAndroid[slot] ?: slot
    fun toRaSlot(androidAxis: Int): Int = androidToSlot[androidAxis] ?: androidAxis
}
