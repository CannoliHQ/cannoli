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
    private val readAspectValue: () -> Float,
    private val settings: ViewportSettings,
) {
    private var active = false

    // The apply below forces aspect_ratio_index to ASPECT_RATIO_CUSTOM, so once a viewport is
    // live the index no longer tells us what the user chose. Remember the last mode and requested
    // aspect derived while the index still reflected their choice, and fall back to them on later
    // refreshes.
    private var rememberedMode: ScalingMode? = null
    private var rememberedAspect: Float = 0f

    // The raw aspect_ratio_index and video_scale_integer RetroArch held the moment before that
    // same apply overwrote them, so shadowedSettings() can hand the in-game menu the value the
    // user actually picked instead of Cannoli's takeover value.
    private var shadowedAspectIdx: String? = null
    private var shadowedIntegerScale: String? = null

    /** Test seam: declares a viewport already applied, as it would be after a prior refresh. */
    fun markActive() { active = true }

    /**
     * DECLINED means there is nothing to do (defaults) or the apply itself failed; a caller has
     * no reason to try again. NOT_READY means the core hasn't reported its geometry yet, which
     * happens transiently right after the surface is created, before content has loaded - a
     * caller that wants the viewport to eventually apply should retry.
     */
    enum class RefreshResult { APPLIED, DECLINED, NOT_READY }

    /**
     * aspect_ratio_index and video_scale_integer as the user had them just before Cannoli's own
     * apply overwrote them with its takeover values. Empty while Cannoli does not own the
     * viewport, so a caller with nothing shadowed reads RetroArch as-is.
     */
    fun shadowedSettings(): Map<String, String> {
        if (!active) return emptyMap()
        val idx = shadowedAspectIdx ?: return emptyMap()
        val integer = shadowedIntegerScale ?: return emptyMap()
        return mapOf(
            "aspect_ratio_index" to idx,
            "video_scale_integer" to integer,
        )
    }

    fun refresh(surfaceWidth: Int, surfaceHeight: Int): RefreshResult {
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
                rememberedAspect = 0f
                shadowedAspectIdx = null
                shadowedIntegerScale = null
            }
            return RefreshResult.DECLINED
        }

        val g = coreGeometry() ?: return RefreshResult.NOT_READY
        val aspectIdx = readAspectIdx()
        val mode: ScalingMode
        val requestedAspect: Float
        if (aspectIdx == ASPECT_RATIO_CUSTOM) {
            mode = rememberedMode ?: ScalingMode.CORE_REPORTED
            requestedAspect = rememberedAspect
        } else {
            mode = when {
                aspectIdx == ASPECT_RATIO_FULL -> ScalingMode.FULLSCREEN
                readIntegerScale() -> ScalingMode.INTEGER
                else -> ScalingMode.CORE_REPORTED
            }
            // 24 fills the region outright and 22 defers to the core's own aspect below; every
            // other index is a deliberate ratio (e.g. 4:3), read from RetroArch's own LUT rather
            // than discarded the way falling through to CORE_REPORTED/INTEGER used to discard it.
            requestedAspect = if (aspectIdx == ASPECT_RATIO_FULL || aspectIdx == ASPECT_RATIO_CORE) {
                0f
            } else {
                readAspectValue().takeIf { it > 0f } ?: 0f
            }
            rememberedMode = mode
            rememberedAspect = requestedAspect
            shadowedAspectIdx = aspectIdx.toString()
            shadowedIntegerScale = readIntegerScale().toString()
        }

        val rect = computeViewport(
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            frameWidth = g[0],
            frameHeight = g[1],
            coreAspectRatio = if (g[3] != 0) g[2].toFloat() / g[3] else 0f,
            requestedAspect = requestedAspect,
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
        return if (ok) RefreshResult.APPLIED else RefreshResult.DECLINED
    }

    private companion object {
        // retroarch/gfx/video_defines.h
        const val ASPECT_RATIO_CORE = 22
        const val ASPECT_RATIO_CUSTOM = 23
        const val ASPECT_RATIO_FULL = 24
    }
}
