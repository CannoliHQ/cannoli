package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledShaderAssetsTest {
    private val bundled = File("src/main/assets/shaders")

    @Test fun `only Slang shaders are bundled`() {
        val files = bundled.walk().filter { it.isFile }.toList()
        assertTrue(files.isNotEmpty())
        assertEquals(emptyList<String>(), files.map { it.extension }.filter { it.startsWith("glsl") })
    }
}
