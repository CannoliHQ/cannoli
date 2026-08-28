package dev.cannoli.core.shader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShaderPragmaTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun shader(name: String, body: String): PresetPass {
        val f = File(tempFolder.root, name).apply {
            parentFile?.mkdirs()
            writeText(body)
        }
        return PresetPass(f.absolutePath)
    }

    @Test fun `a declaration becomes a tunable with its author's range`() {
        val pass = shader(
            "crt.slang",
            """
            #pragma parameter curvature "Curvature" 1.7 0.0 2.0 0.1
            void main() {}
            """.trimIndent(),
        )

        val p = ShaderPragma.parameters(listOf(pass)).single()
        assertEquals("curvature", p.id)
        assertEquals("Curvature", p.desc)
        assertEquals(1.7f, p.default, 0.001f)
        assertEquals(0f, p.min, 0.001f)
        assertEquals(2f, p.max, 0.001f)
        assertEquals(0.1f, p.step, 0.001f)
    }

    // The description holds spaces, so the line cannot simply be split on whitespace.
    @Test fun `a multi word description survives`() {
        val pass = shader("a.slang", """#pragma parameter mask "Mask Dot Width" 1.0 1.0 100.0 1.0""")
        assertEquals("Mask Dot Width", ShaderPragma.parameters(listOf(pass)).single().desc)
    }

    // A row that cannot be moved is worse than one that moves in an arbitrary amount.
    @Test fun `a missing step becomes the range in a hundred parts`() {
        val pass = shader("a.slang", """#pragma parameter warp "Warp" 0.5 0.0 1.0""")
        assertEquals(0.01f, ShaderPragma.parameters(listOf(pass)).single().step, 0.0001f)
    }

    /**
     * VHSPro keeps its whole parameter block in one .inc and includes it from every pass. Without
     * following includes such a chain looks like it has nothing to tune at all.
     */
    @Test fun `parameters declared in an include are found`() {
        File(tempFolder.root, "params.inc").writeText(
            """#pragma parameter noise "Noise" 0.2 0.0 1.0 0.05"""
        )
        val pass = shader(
            "vhs.slang",
            """
            #include "params.inc"
            void main() {}
            """.trimIndent(),
        )

        assertEquals(listOf("noise"), ShaderPragma.parameters(listOf(pass)).map { it.id })
    }

    @Test fun `an include cycle does not spin`() {
        File(tempFolder.root, "a.inc").writeText("#include \"b.inc\"\n")
        File(tempFolder.root, "b.inc").writeText(
            "#include \"a.inc\"\n#pragma parameter x \"X\" 1 0 2 1\n"
        )
        val pass = shader("main.slang", "#include \"a.inc\"\n")

        assertEquals(listOf("x"), ShaderPragma.parameters(listOf(pass)).map { it.id })
    }

    // One name, one value, whichever passes use it. A chain that repeats a preset must not show
    // the same tunable twice.
    @Test fun `a parameter declared by several passes appears once`() {
        val a = shader("a.slang", """#pragma parameter warp "Warp" 0.5 0.0 1.0 0.1""")
        val b = shader("b.slang", """#pragma parameter warp "Warp" 0.9 0.0 1.0 0.1""")

        val found = ShaderPragma.parameters(listOf(a, b, a))
        assertEquals(1, found.size)
        assertEquals(0.5f, found.single().default, 0.001f)
    }

    @Test fun `passes are read in order`() {
        val a = shader("a.slang", """#pragma parameter first "First" 1 0 2 1""")
        val b = shader("b.slang", """#pragma parameter second "Second" 1 0 2 1""")

        assertEquals(listOf("first", "second"), ShaderPragma.parameters(listOf(a, b)).map { it.id })
    }

    @Test fun `a malformed or missing declaration is skipped rather than guessed at`() {
        val pass = shader(
            "a.slang",
            """
            #pragma parameter broken
            #pragma parameter alsobroken "No numbers"
            #pragma parameter ok "Fine" 1 0 2 1
            """.trimIndent(),
        )

        assertEquals(listOf("ok"), ShaderPragma.parameters(listOf(pass)).map { it.id })
    }

    @Test fun `a shader that is not there contributes nothing`() {
        assertTrue(ShaderPragma.parameters(listOf(PresetPass("/nope/missing.slang"))).isEmpty())
    }
}
