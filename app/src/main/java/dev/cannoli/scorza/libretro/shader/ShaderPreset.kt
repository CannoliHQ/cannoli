package dev.cannoli.scorza.libretro.shader

data class ShaderPreset(
    val basePath: String,
    val passes: List<PassConfig>,
    val parameters: Map<String, ParameterDef>,
    val textures: Map<String, TextureRef>
)

data class PassConfig(
    val shaderPath: String,
    val filterLinear: Boolean = false,
    val scaleType: ScaleType = ScaleType.SOURCE,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val alias: String = "",
    val floatFbo: Boolean = false,
    val wrapRepeat: Boolean = false,
    val mipmap: Boolean = false,
    val frameCountMod: Int = 0
)

enum class ScaleType { SOURCE, VIEWPORT, ABSOLUTE }

data class ParameterDef(
    val id: String,
    val description: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val step: Float
)

data class TextureRef(
    val path: String,
    val filterLinear: Boolean = true,
    val wrapRepeat: Boolean = false,
    val mipmap: Boolean = false
)
