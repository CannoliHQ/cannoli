package dev.cannoli.core.shader

import java.io.File

/** A tunable a shader declares, with the range its author gave it. */
data class PresetParameter(
    val id: String,
    val desc: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val step: Float,
)

/** Reads `#pragma parameter` out of a chain's shaders, so a chain can be described before it runs. */
object ShaderPragma {

    /** In pass order, first declaration winning: one name is one value however many passes use it. */
    fun parameters(passes: List<PresetPass>): List<PresetParameter> {
        val found = LinkedHashMap<String, PresetParameter>()
        val read = mutableSetOf<String>()
        for (pass in passes) collect(File(pass.shader), found, read, depth = 0)
        return found.values.toList()
    }

    // Packs keep their parameter block in an .inc and include it from every pass, so includes are
    // followed. Each file is read once, so a cycle cannot spin.
    private fun collect(
        file: File,
        found: MutableMap<String, PresetParameter>,
        read: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > MAX_INCLUDE_DEPTH) return
        val path = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        if (!read.add(path) || !file.isFile) return
        val text = try { file.readText() } catch (_: Exception) { return }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith(PRAGMA) ->
                    parse(line.removePrefix(PRAGMA).trim())?.let { found.putIfAbsent(it.id, it) }
                line.startsWith(INCLUDE) -> {
                    val name = line.removePrefix(INCLUDE).trim().removeSurrounding("\"")
                    if (name.isNotEmpty()) {
                        collect(File(file.parentFile, name), found, read, depth + 1)
                    }
                }
            }
        }
    }

    /** `name "Some description" default min max [step]`. A missing step is the range in a hundred. */
    private fun parse(body: String): PresetParameter? {
        val open = body.indexOf('"')
        val close = body.indexOf('"', open + 1)
        if (open <= 0 || close <= open) return null
        val id = body.substring(0, open).trim()
        if (id.isEmpty()) return null
        val desc = body.substring(open + 1, close)
        val numbers = body.substring(close + 1).trim().split(Regex("\\s+"))
            .mapNotNull { it.toFloatOrNull() }
        if (numbers.size < 3) return null
        val min = numbers[1]
        val max = numbers[2]
        val step = numbers.getOrNull(3)?.takeIf { it > 0f }
            ?: ((max - min) / 100f).takeIf { it > 0f }
            ?: 1f
        return PresetParameter(id, desc.ifEmpty { id }, numbers[0], min, max, step)
    }

    private const val PRAGMA = "#pragma parameter"
    private const val INCLUDE = "#include"
    private const val MAX_INCLUDE_DEPTH = 4
}
