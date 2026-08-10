package dev.cannoli.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RetroArchConfigComposerTest {

    @Test fun `parse drops blank, comment and malformed lines but keeps the valid ones`() {
        val text = """
            key1 = "value1"

            # a comment line
            this line has no equals sign
            key2 = "value2"
        """.trimIndent()
        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), RetroArchConfigComposer.parse(text))
    }

    @Test fun `parse keeps the first occurrence of a duplicate key`() {
        val text = """
            key1 = "first"
            key1 = "second"
        """.trimIndent()
        assertEquals(mapOf("key1" to "first"), RetroArchConfigComposer.parse(text))
    }

    @Test fun `compose lets a later layer beat an earlier layer and the base`() {
        val base = """key = "base""""
        val layer1 = mapOf("key" to "layer1")
        val layer2 = mapOf("key" to "layer2")
        val layer3 = mapOf("key" to "layer3")
        val result = RetroArchConfigComposer.compose(base, listOf(layer1, layer2, layer3))
        assertEquals(mapOf("key" to "layer3"), RetroArchConfigComposer.parse(result))
    }

    @Test fun `compose uncomments a commented base key when a layer sets it`() {
        val base = """# key = "commented""""
        val result = RetroArchConfigComposer.compose(base, listOf(mapOf("key" to "set")))
        assertEquals("key = \"set\"", result.trim())
        assertFalse(result.trim().startsWith("#"))
    }

    @Test fun `compose appends a layer key the base never had`() {
        val base = """existing = "value""""
        val result = RetroArchConfigComposer.compose(base, listOf(mapOf("new_key" to "new_value")))
        assertEquals(mapOf("existing" to "value", "new_key" to "new_value"), RetroArchConfigComposer.parse(result))
    }
}
