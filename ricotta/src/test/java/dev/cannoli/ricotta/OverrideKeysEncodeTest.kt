package dev.cannoli.ricotta

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the wire format encodeOverrideKeys produces for nativeRaSaveOverride. The native side splits
 * this string on '\n', so the delimiter and the empty-set case are load-bearing.
 */
class OverrideKeysEncodeTest {

    @Test
    fun `an empty set encodes to an empty string`() {
        assertEquals("", EmbeddedRetroArchBridge.encodeOverrideKeys(emptySet()))
    }

    @Test
    fun `a single key encodes with no delimiter`() {
        assertEquals("video_smooth", EmbeddedRetroArchBridge.encodeOverrideKeys(setOf("video_smooth")))
    }

    @Test
    fun `several keys are newline-delimited in iteration order`() {
        assertEquals(
            "run_ahead_enabled\nrun_ahead_frames",
            EmbeddedRetroArchBridge.encodeOverrideKeys(setOf("run_ahead_enabled", "run_ahead_frames")),
        )
    }
}
