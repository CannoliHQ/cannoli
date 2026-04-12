package dev.cannoli.scorza.input

import dev.cannoli.scorza.util.IniParser
import dev.cannoli.scorza.util.IniWriter
import java.io.File

class ProfileManager(private var cannoliRoot: String) {

    private val profilesDir get() = File(cannoliRoot, "Config/Profiles")

    private fun profileFile(name: String) = File(profilesDir, "$name.ini")

    private fun platformFile(platformTag: String) =
        File(cannoliRoot, "Config/Overrides/systems/$platformTag.ini")

    private fun gameFile(platformTag: String, gameBaseName: String) =
        File(cannoliRoot, "Config/Overrides/Games/$platformTag/$gameBaseName.ini")

    fun ensureDefaults() {
        for (name in listOf(NAVIGATION, DEFAULT_GAME)) {
            val f = profileFile(name)
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                f.writeText("")
            }
        }
        profileFile("Default").delete()
    }

    fun reinitialize(root: String) {
        cannoliRoot = root
        ensureDefaults()
    }

    fun listProfiles(): List<String> {
        val dir = profilesDir
        if (!dir.isDirectory) return listOf(NAVIGATION, DEFAULT_GAME)
        val names = dir.listFiles()
            ?.filter { it.extension.equals("ini", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: return listOf(NAVIGATION, DEFAULT_GAME)
        val custom = names.filter { it !in PROTECTED }
        return listOf(NAVIGATION, DEFAULT_GAME) + custom
    }

    fun listGameProfiles(): List<String> {
        return listProfiles().filter { it != NAVIGATION }
    }

    fun readControls(profileName: String): Map<String, Int> {
        val ini = IniParser.parse(profileFile(profileName))
        val map = mutableMapOf<String, Int>()
        for ((key, value) in ini.getSection("controls")) {
            value.toIntOrNull()?.let { map[key] = it }
        }
        return map
    }

    fun saveControls(profileName: String, controls: Map<String, Int>) {
        IniWriter.mergeWrite(
            profileFile(profileName), "controls",
            controls.mapValues { it.value.toString() }
        )
    }

    fun createProfile(name: String, copyFrom: Map<String, Int> = emptyMap()): Boolean {
        if (name.isBlank() || PROTECTED.any { name.equals(it, ignoreCase = true) }) return false
        val f = profileFile(name)
        if (f.exists()) return false
        f.parentFile?.mkdirs()
        if (copyFrom.isNotEmpty()) {
            saveControls(name, copyFrom)
        } else {
            f.writeText("")
        }
        return true
    }

    fun deleteProfile(name: String): Boolean {
        if (isProtected(name)) return false
        return profileFile(name).delete()
    }

    fun profileExists(name: String): Boolean = profileFile(name).exists()

    fun resolveProfile(platformTag: String, gameBaseName: String): String {
        val gameMeta = IniParser.parse(gameFile(platformTag, gameBaseName)).getSection("meta")
        gameMeta["profile"]?.let { if (profileExists(it)) return it }

        val platformMeta = IniParser.parse(platformFile(platformTag)).getSection("meta")
        platformMeta["profile"]?.let { if (profileExists(it)) return it }

        return DEFAULT_GAME
    }

    fun saveProfileSelection(platformTag: String, gameBaseName: String, profileName: String) {
        val file = gameFile(platformTag, gameBaseName)
        if (profileName == DEFAULT_GAME) {
            if (!file.exists()) return
            val ini = IniParser.parse(file)
            val meta = ini.getSection("meta").toMutableMap()
            meta.remove("profile")
            val sections = ini.sections.toMutableMap()
            if (meta.isEmpty()) sections.remove("meta") else sections["meta"] = meta
            if (sections.any { it.value.isNotEmpty() }) IniWriter.write(file, sections)
            else file.delete()
        } else {
            IniWriter.mergeWrite(file, "meta", mapOf("profile" to profileName))
        }
    }

    companion object {
        const val NAVIGATION = "Cannoli Navigation"
        const val DEFAULT_GAME = "Default Controls"
        val PROTECTED = setOf(NAVIGATION, DEFAULT_GAME)

        fun isProtected(name: String): Boolean = name in PROTECTED
    }
}
