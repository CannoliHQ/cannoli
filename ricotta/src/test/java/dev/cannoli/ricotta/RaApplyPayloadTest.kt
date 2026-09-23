package dev.cannoli.ricotta

import dev.cannoli.igm.MachineValue
import org.junit.Assert.assertEquals
import org.junit.Test

class RaApplyPayloadTest {
    @Test fun `pairs decode to each key and the value it held before`() {
        assertEquals(
            mapOf("video_swap_interval" to MachineValue("1"), "video_frame_delay" to MachineValue("0")),
            decodeMoved(arrayOf("video_swap_interval", "1", "video_frame_delay", "0")),
        )
    }

    @Test fun `a trailing key with no value is dropped`() {
        assertEquals(mapOf("a" to MachineValue("1")), decodeMoved(arrayOf("a", "1", "b")))
    }

    @Test fun `nothing moved decodes to nothing`() {
        assertEquals(emptyMap<String, MachineValue>(), decodeMoved(emptyArray()))
    }
}
