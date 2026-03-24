package dev.cannoli.scorza.libretro.shader

import java.io.File

object PresetParser {

    private val PRESET_EXTENSIONS = setOf("glslp", "slangp")
    private val SHADER_EXTENSIONS = setOf("glsl", "slang")

    fun parse(presetFile: File): ShaderPreset? {
        if (!presetFile.exists()) return null
        val ext = presetFile.extension.lowercase()
        if (ext in SHADER_EXTENSIONS) return parseStandalone(presetFile)
        return parsePreset(presetFile)
    }

    fun parseStandalone(shaderFile: File): ShaderPreset? {
        if (!shaderFile.exists()) return null
        val basePath = shaderFile.parentFile?.absolutePath ?: return null
        val source = shaderFile.readText()
        val parameters = mutableMapOf<String, ParameterDef>()
        extractParameters(source, parameters)
        val pass = PassConfig(
            shaderPath = shaderFile.name,
            filterLinear = false,
            scaleType = ScaleType.VIEWPORT,
            scaleX = 1f, scaleY = 1f
        )
        return ShaderPreset(basePath, listOf(pass), parameters, emptyMap())
    }

    private fun parsePreset(presetFile: File): ShaderPreset? {
        val basePath = presetFile.parentFile?.absolutePath ?: return null
        val props = parseProperties(presetFile.readText())

        val shaderCount = props["shaders"]?.toIntOrNull() ?: return null
        if (shaderCount < 1) return null

        val passes = (0 until shaderCount).map { i -> parsePass(props, i) }
        val parameters = mutableMapOf<String, ParameterDef>()
        val textures = mutableMapOf<String, TextureRef>()

        val textureNames = props["textures"]?.unquote()?.split(";")
            ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        for (name in textureNames) {
            val path = props[name]?.unquote() ?: continue
            textures[name] = TextureRef(
                path = path,
                filterLinear = props["${name}_linear"]?.toBooleanLenient() ?: true,
                wrapRepeat = props["${name}_wrap_mode"]?.equals("repeat", ignoreCase = true) ?: false,
                mipmap = props["${name}_mipmap"]?.toBooleanLenient() ?: false
            )
        }

        for (pass in passes) {
            val shaderFile = File(basePath, pass.shaderPath)
            if (shaderFile.exists()) {
                extractParameters(shaderFile.readText(), parameters)
            }
        }

        val paramOverrides = props["parameters"]?.unquote()?.split(";")
            ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        for (name in paramOverrides) {
            val value = props[name]?.toFloatOrNull() ?: continue
            val existing = parameters[name]
            if (existing != null) {
                parameters[name] = existing.copy(default = value)
            }
        }

        return ShaderPreset(basePath, passes, parameters, textures)
    }

    fun extractParameters(glslSource: String, out: MutableMap<String, ParameterDef>) {
        for (line in glslSource.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("#pragma parameter")) continue
            val parts = trimmed.removePrefix("#pragma parameter").trim()
            val tokens = tokenize(parts)
            if (tokens.size < 5) continue
            val id = tokens[0]
            val desc = tokens[1]
            val default = tokens[2].toFloatOrNull() ?: continue
            val min = tokens[3].toFloatOrNull() ?: continue
            val max = tokens[4].toFloatOrNull() ?: continue
            val step = tokens.getOrNull(5)?.toFloatOrNull() ?: 0.1f
            out.putIfAbsent(id, ParameterDef(id, desc, default, min, max, step))
        }
    }

    fun splitVertexFragment(glslSource: String): Pair<String?, String?> {
        val hasVertexGuard = glslSource.contains("defined(VERTEX)")
        val hasFragmentGuard = glslSource.contains("defined(FRAGMENT)")

        if (!hasVertexGuard && !hasFragmentGuard) {
            return null to glslSource
        }

        val vertex = if (hasVertexGuard) "#define VERTEX\n$glslSource" else null
        val fragment = if (hasFragmentGuard) "#define FRAGMENT\n$glslSource" else null
        return vertex to fragment
    }

    private fun parsePass(props: Map<String, String>, index: Int): PassConfig {
        val shader = props["shader$index"]?.unquote() ?: "pass$index.glsl"
        val filterLinear = props["filter_linear$index"]?.toBooleanLenient() ?: false
        val alias = props["alias$index"]?.unquote() ?: ""

        val scaleTypeStr = props["scale_type$index"]?.unquote()?.lowercase()
        val scaleTypeX = props["scale_type_x$index"]?.unquote()?.lowercase() ?: scaleTypeStr
        val scaleTypeY = props["scale_type_y$index"]?.unquote()?.lowercase() ?: scaleTypeStr
        val scaleType = parseScaleType(scaleTypeX ?: scaleTypeStr)

        val scaleX = props["scale_x$index"]?.toFloatOrNull()
            ?: props["scale$index"]?.toFloatOrNull() ?: 1f
        val scaleY = props["scale_y$index"]?.toFloatOrNull()
            ?: props["scale$index"]?.toFloatOrNull() ?: 1f

        val floatFbo = props["float_framebuffer$index"]?.toBooleanLenient() ?: false
        val wrapRepeat = props["wrap_mode$index"]?.equals("repeat", ignoreCase = true) ?: false
        val mipmap = props["mipmap_input$index"]?.toBooleanLenient() ?: false
        val frameCountMod = props["frame_count_mod$index"]?.toIntOrNull() ?: 0

        return PassConfig(shader, filterLinear, scaleType, scaleX, scaleY, alias,
            floatFbo, wrapRepeat, mipmap, frameCountMod)
    }

    private fun parseScaleType(value: String?): ScaleType = when (value) {
        "viewport" -> ScaleType.VIEWPORT
        "absolute" -> ScaleType.ABSOLUTE
        else -> ScaleType.SOURCE
    }

    private fun parseProperties(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eq = trimmed.indexOf('=')
            if (eq < 0) continue
            val key = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            map[key] = value
        }
        return map
    }

    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < input.length) {
            while (i < input.length && input[i].isWhitespace()) i++
            if (i >= input.length) break
            if (input[i] == '"') {
                i++
                val start = i
                while (i < input.length && input[i] != '"') i++
                tokens.add(input.substring(start, i))
                if (i < input.length) i++
            } else {
                val start = i
                while (i < input.length && !input[i].isWhitespace()) i++
                tokens.add(input.substring(start, i))
            }
        }
        return tokens
    }

    private fun String.unquote(): String =
        if (length >= 2 && first() == '"' && last() == '"') substring(1, length - 1) else this

    private fun String.toBooleanLenient(): Boolean =
        equals("true", ignoreCase = true) || this == "1"
}
