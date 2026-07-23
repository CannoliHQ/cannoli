package dev.cannoli.scorza.libretro

// Guards the vsync-locked pacing path so a display that draws faster than the core's fps
// (e.g. a 90Hz panel ramping up on input while running a 60fps core) cannot advance the
// emulator faster than real time. leadNs tracks how far the emulator has run ahead of the
// wall clock; a frame is skipped only once that lead reaches a full frame, so a matched
// display (draw rate == core fps) never skips and behaves exactly as one-run-per-draw.
class LockedFramePacer {
    private var leadNs = 0L

    fun reset() {
        leadNs = 0L
    }

    fun shouldRunFrame(deltaNs: Long, frameDurationNs: Long): Boolean {
        leadNs -= deltaNs
        if (leadNs < -frameDurationNs) leadNs = -frameDurationNs
        if (leadNs >= frameDurationNs) return false
        leadNs += frameDurationNs
        return true
    }
}
