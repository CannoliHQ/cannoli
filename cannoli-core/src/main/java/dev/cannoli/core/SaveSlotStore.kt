package dev.cannoli.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

class SaveSlotStore(
    private val stateBasePath: String,
    val slotCount: Int = SLOT_COUNT,
) {

    fun statePath(slot: Int): String = StateSlotPaths.statePath(stateBasePath, slot)

    fun thumbnailPath(slot: Int): String = StateSlotPaths.thumbnailPath(stateBasePath, slot)

    private fun progressPath(slot: Int): String = "${statePath(slot)}.ra"

    fun exists(slot: Int): Boolean = File(statePath(slot)).exists()

    fun occupancy(): List<Boolean> = (0 until slotCount).map(::exists)

    fun delete(slot: Int) {
        File(statePath(slot)).delete()
        File(thumbnailPath(slot)).delete()
        File(progressPath(slot)).delete()
    }

    fun thumbnail(slot: Int, maxPx: Int = THUMBNAIL_MAX_PX): Bitmap? {
        val file = File(thumbnailPath(slot))
        if (!file.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }

    fun rotateAutoIntoHistory() {
        if (slotCount < 2) return
        for (slot in slotCount - 1 downTo 2) {
            moveSlot(slot - 1, slot)
        }
        moveSlot(AUTO_SLOT, 1)
    }

    private fun moveSlot(from: Int, to: Int) {
        move(statePath(from), statePath(to))
        move(thumbnailPath(from), thumbnailPath(to))
        move(progressPath(from), progressPath(to))
    }

    private fun move(from: String, to: String) {
        val source = File(from)
        if (!source.exists()) return
        val destination = File(to)
        destination.delete()
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    companion object {
        const val AUTO_SLOT = 0
        const val SLOT_COUNT = 11
        const val THUMBNAIL_MAX_PX = 640
    }
}
