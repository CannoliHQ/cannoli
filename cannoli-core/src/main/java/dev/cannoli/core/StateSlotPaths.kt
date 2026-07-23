package dev.cannoli.core

// Single source of truth for how an IGM save slot maps to on-disk state/thumbnail
// files and to RetroArch's integer state_slot. Slot index 0 is the auto slot;
// 1..N are manual slots where index 1 is the first manual slot (no numeric
// suffix), matching the launcher's historical naming.
object StateSlotPaths {

    fun statePath(stateBasePath: String, slotIndex: Int): String = when {
        slotIndex == 0 -> "$stateBasePath.auto"
        slotIndex == 1 -> stateBasePath
        else -> "$stateBasePath${slotIndex - 1}"
    }

    fun thumbnailPath(stateBasePath: String, slotIndex: Int): String =
        "${statePath(stateBasePath, slotIndex)}.png"

    // The RetroArch state_slot integer that produces statePath(...) for a manual slot.
    fun retroArchStateSlot(slotIndex: Int): Int = slotIndex - 1
}
