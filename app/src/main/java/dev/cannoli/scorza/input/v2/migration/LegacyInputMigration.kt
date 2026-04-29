package dev.cannoli.scorza.input.v2.migration

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.di.CannoliRoot
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegacyInputMigration @Inject constructor(
    @CannoliRoot private val root: File,
    private val clock: () -> String = { Instant.now().toString().replace(":", "-") },
) {

    fun runIfNeeded(): Boolean {
        val paths = CannoliPaths(root)
        val profilesDir = paths.configProfiles
        val overridesDir = paths.configOverrides

        val overridesWithControllerType = overridesDir
            .takeIf { it.exists() }
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension.equals("ini", ignoreCase = true) }
            ?.filter { it.useLines { lines -> lines.any { it.trim().startsWith("controller_type=") } } }
            ?.toList()
            ?: emptyList()

        val needsMigration = profilesDir.exists() || overridesWithControllerType.isNotEmpty()
        if (!needsMigration) return false

        val configBackupDir = File(paths.configDir, "Backup")
        val backupRoot = File(configBackupDir, "input_rewrite_${clock()}")
        backupRoot.mkdirs()

        if (profilesDir.exists()) {
            val backupProfiles = File(backupRoot, "Profiles")
            profilesDir.copyRecursively(backupProfiles, overwrite = true)
            profilesDir.deleteRecursively()
        }

        for (override in overridesWithControllerType) {
            val rel = override.relativeTo(root)
            val backupCopy = File(backupRoot, rel.path)
            backupCopy.parentFile?.mkdirs()
            override.copyTo(backupCopy, overwrite = true)
            stripControllerType(override)
        }

        return true
    }

    private fun stripControllerType(file: File) {
        val originalEndsWithNewline = file.readText().endsWith("\n")
        val cleaned = file.readLines()
            .filterNot { it.trim().startsWith("controller_type=") }
            .joinToString(separator = "\n", postfix = if (originalEndsWithNewline) "\n" else "")
        file.writeText(cleaned)
    }
}
