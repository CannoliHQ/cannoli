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

class ViewportController(
    private val coreGeometry: () -> IntArray?,
    private val applyViewport: (Int, Int, Int, Int) -> Boolean,
    private val clearViewport: (Int, Boolean) -> Boolean,
    private val readAspectIdx: () -> Int,
    private val readIntegerScale: () -> Boolean,
    private val readAspectValue: () -> Float,
    private val settings: ViewportSettings,
) {
    private var active = false

    private var rememberedMode: ScalingMode? = null
    private var rememberedAspect: Float = 0f

    private var shadowedAspectIdx: String? = null
    private var shadowedIntegerScale: String? = null

    fun markActive() { active = true }

    enum class RefreshResult { APPLIED, DECLINED, NOT_READY }

    fun shadowedSettings(): Map<String, String> {
        if (!active) return emptyMap()
        val idx = shadowedAspectIdx ?: return emptyMap()
        val integer = shadowedIntegerScale ?: return emptyMap()
        return mapOf(
            dev.cannoli.igm.RaKeys.ASPECT_RATIO_INDEX to idx,
            dev.cannoli.igm.RaKeys.VIDEO_SCALE_INTEGER to integer,
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
                val restoreIdx = shadowedAspectIdx?.toIntOrNull() ?: readAspectIdx()
                val restoreIntegerScale = shadowedIntegerScale?.toBoolean() ?: readIntegerScale()
                clearViewport(restoreIdx, restoreIntegerScale)
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
        const val ASPECT_RATIO_CORE = 22
        const val ASPECT_RATIO_CUSTOM = 23
        const val ASPECT_RATIO_FULL = 24
    }
}
