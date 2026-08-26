package dev.cannoli.ricotta

import dev.cannoli.ui.ScalingMode
import dev.cannoli.ui.computeViewport

data class ViewportSettings(
    val portraitMarginPx: Int,
    val geometryWidthPct: Int,
    val geometryHeightPct: Int,
    val geometryXPct: Int,
    val geometryYPct: Int,
)

/**
 * Places the game inside the region Screen Geometry defines, less whatever Portrait Margin reserves.
 * The scaling row owns aspect_ratio_index, so the fit obeys it rather than overriding it, and the
 * index is only taken to custom once a setting actually asks for a viewport.
 */
class ViewportController(
    private val coreGeometry: () -> IntArray?,
    private val applyViewport: (Int, Int, Int, Int) -> Boolean,
    private val clearViewport: (Int) -> Boolean,
    private val readAspectIdx: () -> Int,
    private val readIntegerScale: () -> Boolean,
    private val settings: ViewportSettings,
) {
    private var active = false

    // The apply below forces aspect_ratio_index to ASPECT_RATIO_CUSTOM, so once a viewport is
    // live the index no longer tells us what the user chose. Remember the last mode derived
    // while the index still reflected their choice, and fall back to it on later refreshes.
    private var rememberedMode: ScalingMode? = null

    /** Test seam: declares a viewport already applied, as it would be after a prior refresh. */
    fun markActive() { active = true }

    fun refresh(surfaceWidth: Int, surfaceHeight: Int): Boolean {
        val portrait = surfaceWidth < surfaceHeight
        val marginWanted = portrait && settings.portraitMarginPx > 0
        val geometryWanted = settings.geometryWidthPct != 100 ||
            settings.geometryHeightPct != 100 ||
            settings.geometryXPct != 0 ||
            settings.geometryYPct != 0

        if (!marginWanted && !geometryWanted) {
            if (active) {
                clearViewport(readAspectIdx())
                active = false
                rememberedMode = null
            }
            return false
        }

        val g = coreGeometry() ?: return false
        val aspectIdx = readAspectIdx()
        val mode = if (aspectIdx == ASPECT_RATIO_CUSTOM) {
            rememberedMode ?: ScalingMode.CORE_REPORTED
        } else {
            val derived = when {
                aspectIdx == ASPECT_RATIO_FULL -> ScalingMode.FULLSCREEN
                readIntegerScale() -> ScalingMode.INTEGER
                else -> ScalingMode.CORE_REPORTED
            }
            rememberedMode = derived
            derived
        }

        val rect = computeViewport(
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            frameWidth = g[0],
            frameHeight = g[1],
            coreAspectRatio = if (g[3] != 0) g[2].toFloat() / g[3] else 0f,
            rotation = 0,
            scalingMode = mode,
            portraitMarginPx = settings.portraitMarginPx,
            geometryWidthPct = settings.geometryWidthPct,
            geometryHeightPct = settings.geometryHeightPct,
            geometryXPct = settings.geometryXPct,
            geometryYPct = settings.geometryYPct,
        )
        val ok = applyViewport(rect.x, rect.y, rect.w, rect.h)
        if (ok) active = true
        return ok
    }

    private companion object {
        // retroarch/gfx/video_defines.h
        const val ASPECT_RATIO_FULL = 24
        const val ASPECT_RATIO_CUSTOM = 23
    }
}
