package dev.cannoli.ricotta

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SensorQueueDrainParityTest {
    private val patch = File("../patches/android_input.patch").readText()

    @Test fun `losing focus with sensors disabled still drains the queue`() {
        val elseLine = patch.indexOf("+   else")
        assertTrue("missing the added else branch", elseLine >= 0)

        val hunkEnd = patch.indexOf("\n@@", elseLine).let { if (it < 0) patch.length else it }
        val hunk = patch.substring(elseLine, hunkEnd)

        assertTrue(
            "the else branch must drain the sensor queue",
            Regex("""(?m)^\+.*ASensorEventQueue_getEvents\(""").containsMatchIn(hunk),
        )
    }
}
