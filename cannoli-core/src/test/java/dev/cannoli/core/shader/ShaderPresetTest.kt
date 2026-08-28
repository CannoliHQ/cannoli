package dev.cannoli.core.shader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShaderPresetTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun preset(dir: String, name: String, body: String): File =
        File(File(tempFolder.root, dir), name).apply {
            parentFile?.mkdirs()
            writeText(body)
        }

    // A preset names its shaders relative to itself, and a chain is assembled from presets in
    // different folders, so a path that stays relative points at nothing once it is written
    // somewhere else. This is the failure that makes a preset load and then render nothing.
    @Test fun `paths resolve against the preset's own folder`() {
        val f = preset(
            "crt", "a.slangp",
            """
            shaders = 2
            shader0 = shaders/one.slang
            shader1 = ../stock.slang
            """.trimIndent(),
        )
        val parsed = ShaderPreset.parse(f)!!

        assertEquals(
            File(tempFolder.root, "crt/shaders/one.slang").absolutePath,
            parsed.passes[0].shader,
        )
        assertEquals(File(tempFolder.root, "stock.slang").absolutePath, parsed.passes[1].shader)
    }

    @Test fun `an absolute path is left alone`() {
        val parsed = ShaderPreset.from(
            "shaders = 1\nshader0 = /already/absolute.slang\n",
            File(tempFolder.root, "crt"),
        )
        assertEquals("/already/absolute.slang", parsed.passes.single().shader)
    }

    @Test fun `a pass carries its own keys through unchanged`() {
        val parsed = ShaderPreset.from(
            """
            shaders = 1
            shader0 = a.slang
            alias0 = GlowPass
            filter_linear0 = true
            wrap_mode0 = clamp_to_border
            scale_type_x0 = viewport
            scale_x0 = 2.000000
            """.trimIndent(),
            null,
        )
        val pass = parsed.passes.single()

        assertEquals("GlowPass", pass.settings["alias"])
        assertEquals("true", pass.settings["filter_linear"])
        assertEquals("clamp_to_border", pass.settings["wrap_mode"])
        assertEquals("viewport", pass.settings["scale_type_x"])
        assertEquals("2.000000", pass.settings["scale_x"])
    }

    // A parameter whose name ends in a digit looks exactly like a pass key. Only the keys a pass
    // actually owns are treated as pass keys, so "warp2" stays a parameter.
    @Test fun `a parameter ending in a digit is not mistaken for a pass key`() {
        val parsed = ShaderPreset.from(
            """
            shaders = 1
            shader0 = a.slang
            parameters = "warp2"
            warp2 = 0.75
            """.trimIndent(),
            null,
        )

        assertEquals(mapOf("warp2" to "0.75"), parsed.parameters)
        assertTrue(parsed.passes.single().settings.none { it.value == "0.75" })
    }

    @Test fun `textures are read with their own settings`() {
        val parsed = ShaderPreset.from(
            """
            shaders = 1
            shader0 = a.slang
            textures = "scanlines_LUT;color_LUT"
            scanlines_LUT = shaders/scan.png
            scanlines_LUT_linear = false
            color_LUT = shaders/color.png
            color_LUT_linear = true
            """.trimIndent(),
            File("/base"),
        )

        assertEquals(listOf("scanlines_LUT", "color_LUT"), parsed.textures.map { it.id })
        assertEquals("/base/shaders/scan.png", parsed.textures[0].path)
        assertEquals("false", parsed.textures[0].settings["_linear"])
    }

    @Test fun `comments and blank lines are ignored`() {
        val parsed = ShaderPreset.from(
            """
            # a comment
            shaders = 1

            shader0 = a.slang  # trailing note
            """.trimIndent(),
            null,
        )
        assertEquals(1, parsed.passes.size)
        assertEquals("a.slang", parsed.passes.single().shader)
    }

    @Test fun `a preset that is not there parses to nothing`() {
        assertNull(ShaderPreset.parse(File(tempFolder.root, "missing.slangp")))
    }

    // The round trip is what makes editing safe: a preset written back out and read again has to
    // describe the same chain, or every save quietly degrades it.
    @Test fun `a preset survives a round trip`() {
        val original = ShaderPreset.from(
            """
            shaders = 2
            shader0 = /s/one.slang
            alias0 = First
            filter_linear0 = true
            shader1 = /s/two.slang
            scale_type_x1 = viewport
            textures = "LUT"
            LUT = /s/lut.png
            LUT_linear = true
            parameters = "warp"
            warp = 0.5
            """.trimIndent(),
            null,
        )

        val reparsed = ShaderPreset.from(original.serialise(), null)

        assertEquals(original.passes, reparsed.passes)
        assertEquals(original.textures, reparsed.textures)
        assertEquals(original.parameters, reparsed.parameters)
    }

    @Test fun `appending runs this chain first and then the other`() {
        val a = ShaderPreset(listOf(PresetPass("/a.slang")))
        val b = ShaderPreset(listOf(PresetPass("/b.slang")))

        assertEquals(listOf("/a.slang", "/b.slang"), a.append(b).passes.map { it.shader })
        assertEquals(listOf("/b.slang", "/a.slang"), a.prepend(b).passes.map { it.shader })
    }

    // Retuning passes that were already chosen because something added later happens to share a
    // parameter name would be a change nobody asked for.
    @Test fun `the chain already here wins a clash`() {
        val a = ShaderPreset(
            listOf(PresetPass("/a.slang")),
            listOf(PresetTexture("LUT", "/a/lut.png")),
            mapOf("warp" to "0.1"),
        )
        val b = ShaderPreset(
            listOf(PresetPass("/b.slang")),
            listOf(PresetTexture("LUT", "/b/lut.png")),
            mapOf("warp" to "0.9", "bloom" to "2"),
        )

        val merged = a.append(b)
        assertEquals("/a/lut.png", merged.textures.single().path)
        assertEquals("0.1", merged.parameters["warp"])
        assertEquals("2", merged.parameters["bloom"])
    }

    @Test fun `passes can be removed, moved and retuned`() {
        val chain = ShaderPreset(
            listOf(PresetPass("/a.slang"), PresetPass("/b.slang"), PresetPass("/c.slang"))
        )

        assertEquals(listOf("/a.slang", "/c.slang"), chain.removePass(1).passes.map { it.shader })
        assertEquals(
            listOf("/b.slang", "/a.slang", "/c.slang"),
            chain.movePass(0, 1).passes.map { it.shader },
        )
        assertEquals("true", chain.withPassSetting(0, "filter_linear", "true").passes[0].settings["filter_linear"])
        assertNull(
            chain.withPassSetting(0, "filter_linear", "true")
                .withPassSetting(0, "filter_linear", null).passes[0].settings["filter_linear"],
        )
    }

    @Test fun `an edit outside the chain changes nothing`() {
        val chain = ShaderPreset(listOf(PresetPass("/a.slang")))

        assertEquals(chain, chain.removePass(4))
        assertEquals(chain, chain.movePass(0, 3))
        assertEquals(chain, chain.withPassSetting(9, "filter_linear", "true"))
    }
}
