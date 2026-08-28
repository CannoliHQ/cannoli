package dev.cannoli.ricotta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A setting crosses as name/value pairs from one encoder, so the two describers cannot drift.
 *
 * They did, when the array was positional: the core option branch allocated eight where the
 * RetroArch branch allocated ten, so core options arrived with no machine value, nothing was
 * recorded when the menu snapshotted one, and Discard silently left the change applied.
 */
class RicottaRaSettingFieldParityTest {

    private val bridge = File("jni/ricotta_bridge.c")
    private val decoder = File("src/main/java/dev/cannoli/ricotta/EmbeddedRetroArchBridge.kt")

    private fun source() = bridge.readText()

    @Test fun `the sources are where the test thinks they are`() {
        assertTrue("expected ${bridge.absolutePath} to exist", bridge.exists())
        assertTrue("expected ${decoder.absolutePath} to exist", decoder.exists())
    }

    @Test fun `both describers emit through the one encoder`() {
        assertEquals(
            "each settings describer must return ricotta_fields_to_array",
            2,
            Regex("out = ricotta_fields_to_array\\(").findAll(source()).count(),
        )
    }

    @Test fun `only the encoder allocates a setting array`() {
        assertEquals(
            1,
            Regex("NewObjectArray\\(env,\\s*\\(jsize\\)\\(n \\* 2\\)").findAll(source()).count(),
        )
    }

    @Test fun `a setting carries both namespaces and never just one`() {
        for (field in listOf("machine", "display")) {
            assertEquals(
                "$field must be emitted by both describers",
                2,
                Regex("fields\\[n\\]\\.name = \"$field\"").findAll(source()).count(),
            )
        }
    }

    /**
     * A field name is the contract, so a typo on either side drops that field in silence. Every
     * name the decoder reads has to be one C actually emits.
     */
    @Test fun `every field the decoder reads is one C emits`() {
        val emitted = Regex("fields\\[n\\]\\.name = \"([a-z]+)\"")
            .findAll(source()).map { it.groupValues[1] }.toSet()
        val read = Regex("fields\\[\"([a-z]+)\"\\]")
            .findAll(decoder.readText()).map { it.groupValues[1] }.toSet()

        assertTrue("C emits no fields at all", emitted.isNotEmpty())
        assertTrue("decoder reads fields C never emits: ${read - emitted}", (read - emitted).isEmpty())
    }

    @Test fun `the option pair names agree on both sides`() {
        assertTrue(
            "C must emit opt<n>.machine and opt<n>.display",
            Regex("\"opt%[a-z]+\\.machine\"").containsMatchIn(source()) &&
                Regex("\"opt%[a-z]+\\.display\"").containsMatchIn(source()),
        )
        val text = decoder.readText()
        assertTrue(text.contains("opt\$it.machine") && text.contains("opt\$it.display"))
    }
}
