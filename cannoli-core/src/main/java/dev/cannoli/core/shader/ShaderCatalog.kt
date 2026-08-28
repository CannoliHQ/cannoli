package dev.cannoli.core.shader

import java.io.File

/** A row in the shader browser: either a folder to enter or a preset to apply. */
/** [path] is absolute for a preset and empty for a folder, so a caller can tell which is applied. */
/** [path] is absolute for a preset and empty for a folder. */
data class ShaderEntry(val name: String, val isFolder: Boolean, val path: String = "")

/**
 * Browsing the shader database on disk.
 *
 * The archives ship their own taxonomy, about sixty category folders, and it is trusted rather than
 * reorganised: RetroArch's own shader browser is a plain extension-filtered file listing for the
 * same reason. A level is folders first and then presets, each sorted, so the packs stay together
 * instead of being scattered through one alphabetical run.
 *
 * Which format is offered follows the video driver, because RetroArch's GL driver refuses anything
 * that is not GLSL and its Vulkan driver takes slang. Filtering here rather than showing a user a
 * preset that cannot load is the whole reason this knows about drivers at all.
 */
object ShaderCatalog {

    const val DIR = "Shaders"

    /** The user's own presets. Named for custom.cfg, and left alone by the download for the same reason. */
    const val CUSTOM_DIR = "Custom"

    /** What RetroArch calls the preset it writes when combining two others. */
    private const val SCRATCH_PRESET = "retroarch"

    /** Preset extension the active video driver can load. */
    fun presetExtension(videoDriver: String): String =
        if (videoDriver.equals("vulkan", ignoreCase = true)) "slangp" else "glslp"

    fun shadersDir(cannoliRoot: File): File = File(cannoliRoot, DIR)

    /**
     * One level of the browser. [relative] is empty for the root, otherwise folder names from it.
     *
     * A folder is offered only when something under it can actually load, so entering one is never
     * a dead end. That check is recursive because the packs nest, and it stops at the first hit
     * rather than counting.
     */
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

    /** Absolute path of a preset, for handing to RetroArch. */
    fun presetFile(shadersDir: File, relative: List<String>, name: String, videoDriver: String): File =
        File(
            relative.fold(shadersDir) { acc, part -> File(acc, part) },
            "$name.${presetExtension(videoDriver)}",
        )

    /**
     * RetroArch's own scratch preset, which it rewrites every time two presets are combined.
     *
     * It lives at the root of the shader directory because that is where RetroArch puts it, and it
     * holds whatever the last append happened to produce, so offering it would be offering a
     * leftover. Only at the root: a pack of its own may legitimately name a preset this.
     */
    private fun isScratchPreset(file: File, relative: List<String>): Boolean =
        relative.isEmpty() && file.nameWithoutExtension.equals(SCRATCH_PRESET, ignoreCase = true)

    // The archives keep their shader sources in a top-level "shaders" folder beside the presets.
    // It holds no presets of its own, so it would already be filtered out, but naming it costs
    // nothing and saves walking several thousand files to discover that every time.
    private fun isSourceDir(dir: File, relative: List<String>): Boolean =
        relative.isEmpty() && dir.name.equals("shaders", ignoreCase = true)

    /**
     * Whether entering a folder is worth offering.
     *
     * The index answers it without touching the disk. A folder it does not mention is checked
     * directly, which is what keeps hand-placed shaders working against an index built before they
     * arrived: the fallback costs one subtree rather than the whole tree.
     */
    private fun leadsToPreset(
        dir: File,
        relative: List<String>,
        ext: String,
        index: ShaderIndex.Index?,
    ): Boolean {
        if (index != null) {
            val path = (relative + dir.name).joinToString("/")
            if (path in index.forExtension(ext)) return true
            // Seen by the walk and not listed for this format means it genuinely leads nowhere.
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
