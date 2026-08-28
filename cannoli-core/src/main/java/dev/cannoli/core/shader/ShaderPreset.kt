package dev.cannoli.core.shader

import java.io.File

/**
 * [shader] is absolute: presets name their shaders relative to themselves, and a chain is assembled
 * from presets in different folders.
 *
 * [settings] holds the pass's own keys with the index stripped, carried verbatim so keys the menu
 * does not understand survive a round trip.
 */
data class PresetPass(
    val shader: String,
    val settings: Map<String, String> = emptyMap(),
)

/** [path] is absolute, for the same reason [PresetPass.shader] is. */
data class PresetTexture(
    val id: String,
    val path: String,
    val settings: Map<String, String> = emptyMap(),
)

/** A shader preset, parsed into something Cannoli can edit and write back. */
data class ShaderPreset(
    val passes: List<PresetPass> = emptyList(),
    val textures: List<PresetTexture> = emptyList(),
    /** Values for the tunables the shaders declare, by name. */
    val parameters: Map<String, String> = emptyMap(),
) {

    /** This chain then [other]'s. On a texture or parameter clash this chain wins. */
    fun append(other: ShaderPreset): ShaderPreset = ShaderPreset(
        passes = passes + other.passes,
        textures = (textures + other.textures).distinctBy { it.id },
        parameters = other.parameters + parameters,
    )

    fun prepend(other: ShaderPreset): ShaderPreset = other.append(this)

    fun removePass(index: Int): ShaderPreset =
        if (index !in passes.indices) this
        else copy(passes = passes.filterIndexed { i, _ -> i != index })

    fun movePass(from: Int, to: Int): ShaderPreset {
        if (from !in passes.indices || to !in passes.indices || from == to) return this
        val moved = passes.toMutableList()
        moved.add(to, moved.removeAt(from))
        return copy(passes = moved)
    }

    fun withPassSetting(index: Int, key: String, value: String?): ShaderPreset {
        if (index !in passes.indices) return this
        val pass = passes[index]
        val settings = pass.settings.toMutableMap()
        if (value == null) settings.remove(key) else settings[key] = value
        return copy(passes = passes.toMutableList().also { it[index] = pass.copy(settings = settings) })
    }

    fun withParameter(name: String, value: String): ShaderPreset =
        copy(parameters = parameters + (name to value))

    /** Paths are written absolute, so the file can sit anywhere without its references moving. */
    fun serialise(): String = buildString {
        append("shaders = \"${passes.size}\"\n")
        passes.forEachIndexed { i, pass ->
            append("shader$i = \"${pass.shader}\"\n")
            for ((key, value) in pass.settings) append("$key$i = \"$value\"\n")
        }
        if (textures.isNotEmpty()) {
            append("textures = \"${textures.joinToString(";") { it.id }}\"\n")
            for (texture in textures) {
                append("${texture.id} = \"${texture.path}\"\n")
                for ((key, value) in texture.settings) append("${texture.id}$key = \"$value\"\n")
            }
        }
        if (parameters.isNotEmpty()) {
            append("parameters = \"${parameters.keys.joinToString(";")}\"\n")
            for ((name, value) in parameters) append("$name = \"$value\"\n")
        }
    }

    companion object {
        /** Listed rather than matched by shape, or a parameter named "warp2" reads as pass 2. */
        private val PASS_KEYS = setOf(
            "shader", "alias", "filter_linear", "wrap_mode", "mipmap_input",
            "float_framebuffer", "srgb_framebuffer", "frame_count_mod",
            "scale_type", "scale_type_x", "scale_type_y", "scale", "scale_x", "scale_y",
        )

        private val TEXTURE_SUFFIXES = listOf("_linear", "_wrap_mode", "_mipmap")

        fun parse(file: File): ShaderPreset? = try {
            if (file.isFile) from(file.readText(), file.parentFile) else null
        } catch (_: Exception) {
            null
        }

        /** [base] is what relative paths resolve against. */
        fun from(text: String, base: File?): ShaderPreset {
            val entries = LinkedHashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val cut = line.indexOf('=')
                if (cut <= 0) continue
                entries[line.substring(0, cut).trim()] =
                    line.substring(cut + 1).trim().removeSurrounding("\"")
            }

            val count = entries["shaders"]?.toIntOrNull() ?: 0
            val passes = (0 until count).mapNotNull { i ->
                val shader = entries["shader$i"] ?: return@mapNotNull null
                val settings = LinkedHashMap<String, String>()
                for (key in PASS_KEYS) {
                    if (key == "shader") continue
                    entries["$key$i"]?.let { settings[key] = it }
                }
                PresetPass(absolute(shader, base), settings)
            }

            val textures = entries["textures"]
                .orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { id ->
                    val path = entries[id] ?: return@mapNotNull null
                    val settings = LinkedHashMap<String, String>()
                    for (suffix in TEXTURE_SUFFIXES) entries["$id$suffix"]?.let { settings[suffix] = it }
                    PresetTexture(id, absolute(path, base), settings)
                }

            val parameters = entries["parameters"]
                .orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { name -> entries[name]?.let { name to it } }
                .toMap()

            return ShaderPreset(passes, textures, parameters)
        }

        private fun absolute(path: String, base: File?): String = when {
            base == null -> path
            File(path).isAbsolute -> path
            else -> File(base, path).normalize().absolutePath
        }
    }
}
