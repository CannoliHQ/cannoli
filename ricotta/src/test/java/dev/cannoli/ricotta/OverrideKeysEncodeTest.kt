package dev.cannoli.ricotta

import org.junit.Assert.assertEquals
import org.junit.Test

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
