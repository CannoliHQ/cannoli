package dev.cannoli.core.shader

import java.io.File

object ShaderIndex {

    const val FILE_NAME = ".shader_index"

    data class Index(val glsl: Set<String>, val slang: Set<String>, val all: Set<String>) {
        fun forExtension(ext: String): Set<String> =
            if (ext.equals("slangp", ignoreCase = true)) slang else glsl
    }

    fun file(shadersDir: File): File = File(shadersDir, FILE_NAME)

    @Synchronized
    fun build(shadersDir: File): Index {
        val glsl = mutableSetOf<String>()
        val slang = mutableSetOf<String>()
        val all = mutableSetOf<String>()
        visit(shadersDir, emptyList(), glsl, slang, all)
        val index = Index(glsl, slang, all)
        write(shadersDir, index)
        return index
    }

    fun load(shadersDir: File): Index? {
        val f = file(shadersDir)
        if (!f.isFile) return null
        val glsl = mutableSetOf<String>()
        val slang = mutableSetOf<String>()
        val all = mutableSetOf<String>()
        return try {
            f.forEachLine { line ->
                val cut = line.lastIndexOf('\t')
                if (cut <= 0) return@forEachLine
                val path = line.substring(0, cut)
                val flags = line.substring(cut + 1)
                all.add(path)
                if (flags.contains('g')) glsl.add(path)
                if (flags.contains('s')) slang.add(path)
            }
            Index(glsl, slang, all)
        } catch (_: Exception) {
            null
        }
    }

    private fun write(shadersDir: File, index: Index) {
        val text = buildString {
            for (path in index.all.sorted()) {
                append(path).append('\t')
                if (path in index.glsl) append('g')
                if (path in index.slang) append('s')
                append('\n')
            }
        }
        try {
            file(shadersDir).writeText(text)
        } catch (_: Exception) {
        }
    }

    private fun visit(
        dir: File,
        relative: List<String>,
        glsl: MutableSet<String>,
        slang: MutableSet<String>,
        all: MutableSet<String>,
    ): Pair<Boolean, Boolean> {
        var hasGlsl = false
        var hasSlang = false
        val children = dir.listFiles() ?: return false to false
        for (child in children) {
            if (child.isDirectory) {
                val (g, s) = visit(child, relative + child.name, glsl, slang, all)
                hasGlsl = hasGlsl || g
                hasSlang = hasSlang || s
            } else {
                if (child.extension.equals("glslp", ignoreCase = true)) hasGlsl = true
                if (child.extension.equals("slangp", ignoreCase = true)) hasSlang = true
            }
        }
        if (relative.isNotEmpty()) {
            val path = relative.joinToString("/")
            all.add(path)
            if (hasGlsl) glsl.add(path)
            if (hasSlang) slang.add(path)
        }
        return hasGlsl to hasSlang
    }
}
