package dev.cannoli.scorza.libretro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import dev.cannoli.core.StateSlotPaths
import dev.cannoli.igm.SaveSlotManager as SharedSlotManager

typealias Slot = SharedSlotManager.Slot

class SaveSlotManager(private val stateBasePath: String) {

    val slots = SharedSlotManager().slots

    fun statePath(slot: Slot): String = StateSlotPaths.statePath(stateBasePath, slot.index)

    fun thumbnailPath(slot: Slot): String = StateSlotPaths.thumbnailPath(stateBasePath, slot.index)

    private fun raProgressPath(slot: Slot): String = "${statePath(slot)}.ra"

    fun stateExists(slot: Slot): Boolean = File(statePath(slot)).exists()

    fun deleteState(slot: Slot) {
        File(statePath(slot)).delete()
        File(thumbnailPath(slot)).delete()
        File(raProgressPath(slot)).delete()
    }

    fun loadThumbnail(slot: Slot): Bitmap? {
        val f = File(thumbnailPath(slot))
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
    }
}
