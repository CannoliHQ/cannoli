package dev.cannoli.core.shader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShaderCatalogTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun root(): File = File(tempFolder.root, "Shaders").apply { mkdirs() }

    private fun file(dir: File, name: String): File =
        File(dir, name).apply { parentFile?.mkdirs(); writeText("shaders = 0\n") }

    private fun names(entries: List<ShaderEntry>) = entries.map { it.name }

    @Test fun `the driver decides which format is offered`() {
        assertEquals("slangp", ShaderCatalog.presetExtension("vulkan"))
        assertEquals("glslp", ShaderCatalog.presetExtension("gl"))
        assertEquals("glslp", ShaderCatalog.presetExtension("glcore"))
        assertEquals("glslp", ShaderCatalog.presetExtension(""))
    }

    @Test fun `a level lists folders first then presets, each sorted`() {
        val r = root()
        file(r, "zeta.glslp")
        file(r, "alpha.glslp")
        file(File(r, "zpack"), "one.glslp")
        file(File(r, "apack"), "one.glslp")

        assertEquals(listOf("apack", "zpack", "alpha", "zeta"), names(ShaderCatalog.list(r, emptyList(), "gl")))
    }

    @Test fun `presets the driver cannot load are not offered`() {
        val r = root()
        file(r, "only-glsl.glslp")
        file(r, "only-slang.slangp")

        assertEquals(listOf("only-slang"), names(ShaderCatalog.list(r, emptyList(), "vulkan")))
        assertEquals(listOf("only-glsl"), names(ShaderCatalog.list(r, emptyList(), "gl")))
    }

    @Test fun `a folder with nothing loadable is not offered`() {
        val r = root()
        file(File(r, "slang-only-pack"), "x.slangp")
        file(File(r, "empty-pack"), "readme.txt")

        assertTrue(names(ShaderCatalog.list(r, emptyList(), "vulkan")).contains("slang-only-pack"))
        assertFalse(names(ShaderCatalog.list(r, emptyList(), "gl")).contains("slang-only-pack"))
        assertFalse(names(ShaderCatalog.list(r, emptyList(), "vulkan")).contains("empty-pack"))
    }

    @Test fun `a folder is offered when a preset is buried deeper in it`() {
        val r = root()
        file(File(File(r, "pack"), "nested"), "deep.slangp")

        assertEquals(listOf("pack"), names(ShaderCatalog.list(r, emptyList(), "vulkan")))
    }

    @Test fun `the archives source folder is never offered at the root`() {
        val r = root()
        file(File(r, "shaders"), "crt.glslp")

        assertFalse(names(ShaderCatalog.list(r, emptyList(), "gl")).contains("shaders"))
        file(File(File(r, "pack"), "shaders"), "inner.glslp")
        assertEquals(listOf("shaders"), names(ShaderCatalog.list(r, listOf("pack"), "gl")))
    }

    @Test fun `descending lists that folder rather than the root`() {
        val r = root()
        file(r, "top.glslp")
        file(File(r, "pack"), "inner.glslp")

        assertEquals(listOf("inner"), names(ShaderCatalog.list(r, listOf("pack"), "gl")))
    }

    @Test fun `a missing folder lists nothing`() {
        assertTrue(ShaderCatalog.list(root(), listOf("nope"), "gl").isEmpty())
    }

    @Test fun `the index answers instead of walking, and an unknown folder still works`() {
        val r = root()
        file(File(File(r, "deep"), "nested"), "x.slangp")
        file(File(r, "glsl-only"), "y.glslp")
        val index = ShaderIndex.build(r)

        assertEquals(listOf("deep"), names(ShaderCatalog.list(r, emptyList(), "vulkan", index)))
        assertEquals(listOf("glsl-only"), names(ShaderCatalog.list(r, emptyList(), "gl", index)))

        file(File(r, "added-later"), "z.slangp")
        assertTrue(names(ShaderCatalog.list(r, emptyList(), "vulkan", index)).contains("added-later"))
    }

    @Test fun `a rebuilt index round trips through the file`() {
        val r = root()
        file(File(r, "pack"), "a.slangp")
        file(File(r, "other"), "b.glslp")
        val built = ShaderIndex.build(r)
        val loaded = ShaderIndex.load(r)

        assertEquals(built.slang, loaded?.slang)
        assertEquals(built.glsl, loaded?.glsl)
        assertEquals(built.all, loaded?.all)
        assertTrue(built.all.contains("pack") && built.all.contains("other"))
    }

    @Test fun `the index file is never offered as a row`() {
        val r = root()
        file(r, "real.glslp")
        ShaderIndex.build(r)

        assertEquals(listOf("real"), names(ShaderCatalog.list(r, emptyList(), "gl", ShaderIndex.load(r))))
    }

    @Test fun `RetroArch's scratch preset is never offered at the root`() {
        val r = root()
        file(r, "retroarch.glslp")
        file(r, "real.glslp")

        assertEquals(listOf("real"), names(ShaderCatalog.list(r, emptyList(), "gl")))
        file(File(r, "pack"), "retroarch.glslp")
        assertEquals(listOf("retroarch"), names(ShaderCatalog.list(r, listOf("pack"), "gl")))
    }

    @Test fun `a preset resolves to a real path in its folder`() {
        val r = root()
        val f = file(File(r, "pack"), "crt.slangp")

        assertEquals(f, ShaderCatalog.presetFile(r, listOf("pack"), "crt", "vulkan"))
    }
}
