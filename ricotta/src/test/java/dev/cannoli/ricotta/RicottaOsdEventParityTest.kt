package dev.cannoli.ricotta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RicottaOsdEventParityTest {

    private val header = File("jni/ricotta_osd.h")

    private fun headerValues(): Map<String, Int> {
        val regex = Regex("""RICOTTA_OSD_([A-Z_]+)\s*=\s*(\d+)""")
        return regex.findAll(header.readText()).associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }

    private fun kotlinValues(): Map<String, Int> =
        RicottaOsdEvent::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType && '$' !in it.name }
            .associate { it.name to it.getInt(RicottaOsdEvent) }

    @Test fun `the header is where the test thinks it is`() {
        assertTrue("expected ${header.absolutePath} to exist", header.exists())
    }

    @Test fun `every header value has the same kotlin value`() {
        assertEquals(headerValues(), kotlinValues())
    }
}
