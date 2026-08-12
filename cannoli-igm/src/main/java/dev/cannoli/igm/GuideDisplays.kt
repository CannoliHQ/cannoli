package dev.cannoli.igm

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

data class DisplayCandidate(
    val displayId: Int,
    val isValid: Boolean,
    val isOff: Boolean,
    val isPrivate: Boolean,
)

object GuideDisplays {

    fun select(candidates: List<DisplayCandidate>): Int? = candidates
        .filter {
            it.displayId != Display.DEFAULT_DISPLAY && it.isValid && !it.isOff && !it.isPrivate
        }
        .minByOrNull { it.displayId }
        ?.displayId

    // DISPLAY_CATEGORY_PRESENTATION would work on the Thor, whose second panel does carry
    // FLAG_PRESENTATION, but that flag is not guaranteed on other dual-screen hardware.
    fun secondDisplayId(context: Context): Int? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        return select(
            manager.displays.map { display ->
                DisplayCandidate(
                    displayId = display.displayId,
                    isValid = display.isValid,
                    isOff = display.state == Display.STATE_OFF,
                    isPrivate = display.flags and Display.FLAG_PRIVATE != 0,
                )
            }
        )
    }
}
