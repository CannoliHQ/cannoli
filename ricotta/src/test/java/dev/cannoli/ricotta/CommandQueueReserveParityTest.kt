package dev.cannoli.ricotta

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandQueueReserveParityTest {
    private val bridge = File("jni/ricotta_bridge.c").readText()

    private fun body(signature: String): String {
        val start = bridge.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        return bridge.substring(start, bridge.indexOf("\n}\n", start))
    }

    @Test fun `the reserve is applied to menu writes only`() {
        val b = body("static int ricotta_enqueue_entry(ricotta_cmd_entry entry)")
        assertTrue("must refer to RICOTTA_CMD_RESERVE", b.contains("RICOTTA_CMD_RESERVE"))
        assertTrue("must refer to RICOTTA_QCMD_RA_SET", b.contains("RICOTTA_QCMD_RA_SET"))
    }
}
