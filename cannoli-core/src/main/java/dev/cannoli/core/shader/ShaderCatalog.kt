package dev.cannoli.core.shader

import java.io.File

data class ShaderEntry(val name: String, val isFolder: Boolean, val path: String = "")

object ShaderCatalog {

    const val DIR = "Shaders"

    const val CUSTOM_DIR = "Custom"

    private const val SCRATCH_PRESET = "retroarch"

    fun presetExtension(videoDriver: String): String =
        if (videoDriver.equals("vulkan", ignoreCase = true)) "slangp" else "glslp"

    fun shadersDir(cannoliRoot: File): File = File(cannoliRoot, DIR)

    fun list(
        shadersDir: File,
        relative: List<String>,
        videoDriver: String,
        index: ShaderIndex.Index? = null,
    ): List<ShaderEntry> {
        val ext = presetExtension(videoDriver)
        val dir = relative.fold(shadersDir) { acc, name -> File(acc, name) }
        if (!dir.isDirectory) return emptyList()
        val children = dir.listFiles() ?: return emptyList()

        val folders = children
            .filter { !it.name.startsWith(".") && it.isDirectory && !isSourceDir(it, relative) && leadsToPreset(it, relative, ext, index) }
            .map { ShaderEntry(it.name, isFolder = true) }
            .sortedBy { it.name.lowercase() }

        val presets = children
            .filter {
                !it.name.startsWith(".") && it.isFile &&
                    it.extension.equals(ext, ignoreCase = true) && !isScratchPreset(it, relative)
            }
            .map { ShaderEntry(it.nameWithoutExtension, isFolder = false, path = it.absolutePath) }
            .sortedBy { it.name.lowercase() }

        return folders + presets
    }

    fun presetFile(shadersDir: File, relative: List<String>, name: String, videoDriver: String): File =
        File(
            relative.fold(shadersDir) { acc, part -> File(acc, part) },
            "$name.${presetExtension(videoDriver)}",
        )

    private fun isScratchPreset(file: File, relative: List<String>): Boolean =
        relative.isEmpty() && file.nameWithoutExtension.equals(SCRATCH_PRESET, ignoreCase = true)

    private fun isSourceDir(dir: File, relative: List<String>): Boolean =
        relative.isEmpty() && dir.name.equals("shaders", ignoreCase = true)

    private fun leadsToPreset(
        dir: File,
        relative: List<String>,
        ext: String,
        index: ShaderIndex.Index?,
    ): Boolean {
        if (index != null) {
            val path = (relative + dir.name).joinToString("/")
            if (path in index.forExtension(ext)) return true
            if (path in index.all) return false
        }
        return containsPreset(dir, ext)
    }

    private fun containsPreset(dir: File, ext: String): Boolean {
        val children = dir.listFiles() ?: return false
        if (children.any { it.isFile && it.extension.equals(ext, ignoreCase = true) }) return true
        return children.any { it.isDirectory && containsPreset(it, ext) }
    }
}
