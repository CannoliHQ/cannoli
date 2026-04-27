package dev.cannoli.scorza.libretro.shader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PresetParserTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun writeFile(name: String, content: String): File =
        tempFolder.newFile(name).apply { writeText(content) }

    // ---- parse() top-level ----

    @Test fun `nonexistent file returns null`() {
        assertNull(PresetParser.parse(File(tempFolder.root, "missing.glslp")))
    }

    @Test fun `standalone glsl file produces single-pass preset`() {
        val shader = writeFile(
            "passthrough.glsl",
            """
            #pragma parameter brightness "Brightness" 1.0 0.0 2.0 0.05
            void main() {}
            """.trimIndent()
        )
        val preset = PresetParser.parse(shader)
        assertNotNull(preset)
        preset!!
        assertEquals(1, preset.passes.size)
        val pass = preset.passes.first()
        assertEquals("passthrough.glsl", pass.shaderPath)
        assertEquals(ScaleType.VIEWPORT, pass.scaleType)
        assertEquals(1f, pass.scaleX, 0f)
        assertFalse(pass.filterLinear)
        // Parameter pulled from #pragma parameter
        val brightness = preset.parameters["brightness"]
        assertNotNull(brightness)
        assertEquals(1f, brightness!!.default, 0f)
        assertEquals(0f, brightness.min, 0f)
        assertEquals(2f, brightness.max, 0f)
        assertEquals(0.05f, brightness.step, 1e-6f)
    }

    @Test fun `glslp without shaders count returns null`() {
        val preset = writeFile("bad.glslp", "# empty preset\n")
        assertNull(PresetParser.parse(preset))
    }

    @Test fun `glslp with zero shaders returns null`() {
        val preset = writeFile("zero.glslp", "shaders = 0\n")
        assertNull(PresetParser.parse(preset))
    }

    @Test fun `simple two-pass preset reads shader paths and scaling`() {
        writeFile(
            "blur.glsl",
            "void main() {}\n"
        )
        writeFile(
            "composite.glsl",
            "void main() {}\n"
        )
        val preset = writeFile(
            "two.glslp",
            """
            shaders = 2
            shader0 = "blur.glsl"
            filter_linear0 = true
            scale_type0 = source
            scale0 = 0.5
            shader1 = composite.glsl
            scale_type1 = viewport
            scale_x1 = 1.0
            scale_y1 = 1.0
            """.trimIndent()
        )
        val parsed = PresetParser.parse(preset)
        assertNotNull(parsed)
        parsed!!
        assertEquals(2, parsed.passes.size)
        val first = parsed.passes[0]
        assertEquals("blur.glsl", first.shaderPath)
        assertTrue(first.filterLinear)
        assertEquals(ScaleType.SOURCE, first.scaleType)
        assertEquals(0.5f, first.scaleX, 0f)
        assertEquals(0.5f, first.scaleY, 0f)
        val second = parsed.passes[1]
        assertEquals("composite.glsl", second.shaderPath)
        assertEquals(ScaleType.VIEWPORT, second.scaleType)
        assertEquals(1f, second.scaleX, 0f)
    }

    @Test fun `parameter override in glslp replaces the default from shader source`() {
        writeFile(
            "tint.glsl",
            """
            #pragma parameter tint "Tint" 0.5 0.0 1.0 0.01
            void main() {}
            """.trimIndent()
        )
        val preset = writeFile(
            "tint.glslp",
            """
            shaders = 1
            shader0 = tint.glsl
            parameters = "tint"
            tint = 0.8
            """.trimIndent()
        )
        val parsed = PresetParser.parse(preset)!!
        val tint = parsed.parameters["tint"]!!
        assertEquals(0.8f, tint.default, 1e-6f)
        // min/max/step still come from the #pragma
        assertEquals(0f, tint.min, 0f)
        assertEquals(1f, tint.max, 0f)
        assertEquals(0.01f, tint.step, 1e-6f)
    }

    @Test fun `textures section parses paths and filtering flags`() {
        writeFile("dummy.glsl", "void main() {}\n")
        val preset = writeFile(
            "tex.glslp",
            """
            shaders = 1
            shader0 = dummy.glsl
            textures = "noise;mask"
            noise = "noise.png"
            noise_linear = true
            noise_wrap_mode = repeat
            mask = mask.png
            mask_linear = false
            mask_mipmap = true
            """.trimIndent()
        )
        val parsed = PresetParser.parse(preset)!!
        val noise = parsed.textures["noise"]!!
        assertEquals("noise.png", noise.path)
        assertTrue(noise.filterLinear)
        assertTrue(noise.wrapRepeat)
        assertFalse(noise.mipmap)
        val mask = parsed.textures["mask"]!!
        assertEquals("mask.png", mask.path)
        assertFalse(mask.filterLinear)
        assertFalse(mask.wrapRepeat)
        assertTrue(mask.mipmap)
    }

    @Test fun `missing shader file leaves parameters from other passes intact`() {
        writeFile(
            "real.glsl",
            """
            #pragma parameter strength "Strength" 1.0 0.0 4.0 0.1
            void main() {}
            """.trimIndent()
        )
        val preset = writeFile(
            "mix.glslp",
            """
            shaders = 2
            shader0 = real.glsl
            shader1 = ghost.glsl
            """.trimIndent()
        )
        val parsed = PresetParser.parse(preset)!!
        assertEquals(2, parsed.passes.size)
        assertNotNull(parsed.parameters["strength"])
    }

    // ---- extractParameters ----

    @Test fun `extractParameters skips lines without enough tokens`() {
        val out = mutableMapOf<String, ParameterDef>()
        PresetParser.extractParameters(
            """
            #pragma parameter ok "OK" 1.0 0.0 2.0 0.1
            #pragma parameter bad "Missing args"
            #pragma parameter nondefault "Bad default" not-a-number 0.0 1.0 0.1
            """.trimIndent(),
            out
        )
        assertTrue(out.containsKey("ok"))
        assertFalse(out.containsKey("bad"))
        assertFalse(out.containsKey("nondefault"))
    }

    @Test fun `extractParameters defaults step to 0_1 when omitted`() {
        val out = mutableMapOf<String, ParameterDef>()
        PresetParser.extractParameters(
            "#pragma parameter foo \"Foo\" 1.0 0.0 2.0",
            out
        )
        assertEquals(0.1f, out["foo"]!!.step, 1e-6f)
    }

    @Test fun `extractParameters keeps the first definition seen on duplicates`() {
        val out = mutableMapOf<String, ParameterDef>()
        PresetParser.extractParameters(
            """
            #pragma parameter dup "First" 1.0 0.0 2.0 0.1
            #pragma parameter dup "Second" 5.0 0.0 10.0 0.5
            """.trimIndent(),
            out
        )
        assertEquals("First", out["dup"]!!.description)
        assertEquals(1f, out["dup"]!!.default, 0f)
    }

    @Test fun `extractParameters parses quoted descriptions with spaces`() {
        val out = mutableMapOf<String, ParameterDef>()
        PresetParser.extractParameters(
            "#pragma parameter scanlines \"CRT Scanline Strength\" 0.5 0.0 1.0 0.01",
            out
        )
        assertEquals("CRT Scanline Strength", out["scanlines"]!!.description)
    }

    // ---- splitVertexFragment ----

    @Test fun `splitVertexFragment returns null vertex when no guard present`() {
        val (vert, frag) = PresetParser.splitVertexFragment("void main(){}")
        assertNull(vert)
        assertEquals("void main(){}", frag)
    }

    @Test fun `splitVertexFragment emits both halves with macro defines when guards present`() {
        val src = """
            #if defined(VERTEX)
            void vertmain(){}
            #endif
            #if defined(FRAGMENT)
            void fragmain(){}
            #endif
        """.trimIndent()
        val (vert, frag) = PresetParser.splitVertexFragment(src)
        assertNotNull(vert)
        assertNotNull(frag)
        assertTrue(vert!!.startsWith("#define VERTEX"))
        assertTrue(frag!!.startsWith("#define FRAGMENT"))
    }

    // ---- parseProperties via parsePreset behavior ----

    @Test fun `comments after unquoted values are stripped`() {
        writeFile("dummy.glsl", "void main() {}\n")
        val preset = writeFile(
            "comment.glslp",
            """
            shaders = 1 # primary pass
            shader0 = dummy.glsl
            """.trimIndent()
        )
        val parsed = PresetParser.parse(preset)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.passes.size)
    }

    @Test fun `quoted values keep embedded hash characters`() {
        writeFile("dummy.glsl", "void main() {}\n")
        val preset = writeFile(
            "hashy.glslp",
            """
            shaders = 1
            shader0 = "dummy.glsl"
            textures = "noise"
            noise = "tex#1.png"
            """.trimIndent()
        )
        val parsed = PresetParser.parse(preset)!!
        assertEquals("tex#1.png", parsed.textures["noise"]!!.path)
    }
}
