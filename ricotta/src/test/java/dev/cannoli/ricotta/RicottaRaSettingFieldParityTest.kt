package dev.cannoli.ricotta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
