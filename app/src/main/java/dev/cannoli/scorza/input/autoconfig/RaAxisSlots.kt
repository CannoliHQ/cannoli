package dev.cannoli.scorza.input.autoconfig

// RetroArch cfgs number input_*_axis values as indices into native RA's compacted analog table, not
// as raw Android MotionEvent axis ids. Translate at the cfg boundary; the launcher uses raw Android
// axis ids everywhere internally.
//
// The slot<->axis mapping is exactly RA's Android driver: engine_handle_dpad_getaxisvalue in
// retroarch/input/drivers/android_input.c fills analog_state[slot] for slots 0,1,2,3,6,7,8,9 from
// AXIS_X/Y/Z/RZ/LTRIGGER/RTRIGGER/BRAKE/GAS. Slots 4 and 5 are never filled (RA reads no axis into
// them), and RA's Android driver never reads AXIS_RX(12)/AXIS_RY(13) at all, so a stick on RX/RY has
// no RA slot. android_joypad_axis_state bounds-checks the slot against MAX_AXIS(10), so any raw Android
// id >= 10 written into a cfg (11,14,17,18,22,23...) is a dead binding in native RA. Unknown ids pass
// through unchanged: for the unsupported RX/RY case that yields a slot RA reads as 0 either way.
object RaAxisSlots {
    private val slotToAndroid = mapOf(
        0 to 0,   // AXIS_X       left stick X
        1 to 1,   // AXIS_Y       left stick Y
        2 to 11,  // AXIS_Z       right stick X
        3 to 14,  // AXIS_RZ      right stick Y
        6 to 17,  // AXIS_LTRIGGER
        7 to 18,  // AXIS_RTRIGGER
        8 to 23,  // AXIS_BRAKE   L2 on GAS/BRAKE-style pads
        9 to 22,  // AXIS_GAS     R2
    )
    private val androidToSlot = slotToAndroid.entries.associate { (slot, android) -> android to slot }

    fun toAndroidAxis(slot: Int): Int = slotToAndroid[slot] ?: slot
    fun toRaSlot(androidAxis: Int): Int = androidToSlot[androidAxis] ?: androidAxis
}
