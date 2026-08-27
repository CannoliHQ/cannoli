package dev.cannoli.core.shader

import java.io.File

/**
 * Which folders of the shader tree lead to a preset, worked out once instead of on every render.
 *
 * Deciding whether to offer a folder means knowing whether anything under it can load, and the
 * packs nest, so answering it live meant statting most of a three thousand file tree every time a
 * level was drawn. It is written at the end of an extraction, where minutes are already being spent
 * and nobody is waiting, and read as a plain set afterwards.
 *
 * Being stale is survivable by design: a folder the index does not mention is checked directly, so
 * anything dropped in by hand still appears. The cost of that fallback is one folder rather than a
 * tree, which is the whole difference.
 */
object ShaderIndex {

    /** Inside the tree it describes, so copying the card carries it along. */
    const val FILE_NAME = ".shader_index"

    /**
     * [all] is every folder the walk saw, which is what lets a lookup distinguish "this leads
     * nowhere for your driver" from "this was added after the index was built". Without it a folder
     * holding only the other format would be re-walked on every render, and those are exactly the
     * ones worth not walking.
     */
    data class Index(val glsl: Set<String>, val slang: Set<String>, val all: Set<String>) {
        fun forExtension(ext: String): Set<String> =
            if (ext.equals("slangp", ignoreCase = true)) slang else glsl
    }

    fun file(shadersDir: File): File = File(shadersDir, FILE_NAME)

    /**
     * Walks the tree once and records every folder with a preset beneath it, as paths relative to
     * [shadersDir] using / regardless of platform.
     *
     * Serialised because the archives extract in parallel and each builds when its own extraction
     * ends, so two builds can otherwise interleave and write the file over each other. Whichever
     * runs last still sees a complete tree: a build only starts after its own archive is finished,
     * so the later one is looking at both.
     */
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

    /** The stored index, or null when there is none to read. */
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
            // Every folder, including the ones holding nothing: an absent line means unknown, and a
            // line with no flags means known to lead nowhere. The two must stay distinguishable.
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

    // Returns what this subtree holds, so a parent inherits from its children in one pass rather
    // than each level rediscovering the same answer.
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
