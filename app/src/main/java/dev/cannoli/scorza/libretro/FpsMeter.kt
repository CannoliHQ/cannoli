package dev.cannoli.scorza.libretro

class FpsMeter(private val emaAlpha: Double = 0.05) {

    @Volatile var fps = 0f; private set
    @Volatile var frameTimeMs = 0f; private set

    private var hasLast = false
    private var lastNs = 0L
    private var emaFrameNs = 0.0

    fun tick(nowNs: Long) {
        if (hasLast) {
            val delta = (nowNs - lastNs).toDouble()
            emaFrameNs = if (emaFrameNs == 0.0) delta
                         else emaFrameNs * (1.0 - emaAlpha) + delta * emaAlpha
            if (emaFrameNs > 0.0) {
                fps = (1_000_000_000.0 / emaFrameNs).toFloat()
                frameTimeMs = (emaFrameNs / 1_000_000.0).toFloat()
            }
        }
        lastNs = nowNs
        hasLast = true
    }

    fun reset() {
        hasLast = false
        lastNs = 0L
        emaFrameNs = 0.0
        fps = 0f
        frameTimeMs = 0f
    }
}
