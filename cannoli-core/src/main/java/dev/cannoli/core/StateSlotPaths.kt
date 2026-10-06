package dev.cannoli.core

object StateSlotPaths {

    fun statePath(stateBasePath: String, slotIndex: Int): String = when {
        slotIndex == 0 -> "$stateBasePath.auto"
        slotIndex == 1 -> stateBasePath
        else -> "$stateBasePath${slotIndex - 1}"
    }

    fun thumbnailPath(stateBasePath: String, slotIndex: Int): String =
        "${statePath(stateBasePath, slotIndex)}.png"

    fun retroArchStateSlot(slotIndex: Int): Int = slotIndex - 1
}
