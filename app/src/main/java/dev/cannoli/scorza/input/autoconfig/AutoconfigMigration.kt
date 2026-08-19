package dev.cannoli.scorza.input.autoconfig

import java.io.File

// v2 is a breaking upgrade, so this rewrites rather than tolerating the old shape. Pre-v2 files
// carry neither cannoli_source nor cannoli_builtin, so provenance is read from the old boolean.
class AutoconfigMigration(
    private val dirProvider: () -> File?,
    private val bundledNamesProvider: () -> Set<String>,
) {

    fun migrate() {
        val dir = dirProvider() ?: return
        val files = dir.listFiles { f: File -> f.isFile && f.extension.equals("cfg", true) } ?: return
        val bundledNames = bundledNamesProvider()
        val claimed = mutableMapOf<String, File>()
        // Recorded before any write touches the winning file, since rewriting it in place bumps
        // its mtime to now and would otherwise make it look newest against a later collision.
        val claimedMtime = mutableMapOf<String, Long>()

        for (file in files.sortedBy { it.name }) {
            val mtime = file.lastModified()
            val entry = runCatching { RetroArchCfgParser.parse(file.readText(), fileName = file.name) }.getOrNull()
                ?: continue
            if (entry.provenance != null) continue

            if (!entry.isUserOwned) {
                // Unkeyed and not user-owned: only prunable if it is a name we currently ship.
                // RetroArch reads this same directory, so an unkeyed cfg under any other name may
                // be one RetroArch itself wrote, or one the user hand-dropped in, and neither
                // carries a cannoli_ key to tell it apart from a stale curated one.
                if (file.name in bundledNames) file.delete()
                continue
            }

            val target = File(dir, "${file.name.substringBeforeLast(".cfg").dropDescriptorSuffix()}.cfg")
            val existing = claimed[target.name]
            if (existing != null) {
                val existingMtime = claimedMtime.getValue(target.name)
                park(dir, if (existingMtime >= mtime) file else existing)
                if (existingMtime >= mtime) continue
            }
            claimed[target.name] = file
            claimedMtime[target.name] = mtime
            file.writeText(rewrite(file.readText()))
            if (file.name != target.name) {
                target.delete()
                file.renameTo(target)
                claimed[target.name] = target
            }
        }
    }

    private fun rewrite(text: String): String =
        text.lineSequence()
            .filterNot { it.trimStart().startsWith("cannoli_user") }
            .filterNot { it.trimStart().startsWith("cannoli_descriptor") }
            .plus("cannoli_source = \"USER\"")
            .joinToString("\n") + "\n"

    private fun park(dir: File, file: File) {
        val parked = File(dir, "parked").apply { mkdirs() }
        file.renameTo(File(parked, file.name))
    }

    // Ids used to end in six hex characters of the descriptor hash. Per-model scoping drops it.
    private fun String.dropDescriptorSuffix(): String =
        replace(Regex("_[0-9a-f]{6}$"), "")
}
