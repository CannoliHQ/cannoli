package dev.cannoli.scorza.input.autoconfig

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// v2 is a breaking upgrade, so this rewrites rather than tolerating the old shape. Pre-v2 files
// carry neither cannoli_source nor cannoli_builtin, so provenance is read from the old boolean.
class AutoconfigMigration(
    private val dirProvider: () -> File?,
    private val bundledNamesProvider: () -> Set<String>,
) {

    fun migrate() {
        val dir = dirProvider() ?: return
        val stamp = File(dir, STAMP_FILE)
        // One-shot: the seeder writes freshly bundled cfgs into this same directory right after
        // this runs, and those cfgs are unkeyed (cannoli_source lands in a later task). Running
        // this again on a later boot would see them as pre-v2 leftovers and delete them, with
        // nothing to restore them since the seeder's own stamp would already be up to date.
        if (stamp.exists()) return
        dir.mkdirs()
        val bundledNames = bundledNamesProvider()
        val files = dir.listFiles { f: File -> f.isFile && f.extension.equals("cfg", true) } ?: return
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
                val existingIsNewer = claimedMtime.getValue(target.name) >= mtime
                park(dir, if (existingIsNewer) file else existing)
                if (existingIsNewer) continue
            }
            claimed[target.name] = file
            claimedMtime[target.name] = mtime
            writeAtomic(file, rewrite(file.readText()))
            if (file.name != target.name) {
                clearTargetSlot(dir, target, bundledNames)
                file.renameTo(target)
                claimed[target.name] = target
            }
        }

        stamp.writeText("1")
    }

    // The rename below needs target's path free. It may already be occupied by a file this run
    // never touched: one already migrated (kill-then-rerun left both the old suffixed name and
    // the renamed target on disk), one the fail-safe skipped as unreadable, or a foreign cfg the
    // rules say to leave alone. Only a file that would have been prunable on its own is deleted;
    // everything else is parked so nothing user-owned is ever destroyed.
    private fun clearTargetSlot(dir: File, target: File, bundledNames: Set<String>) {
        if (!target.exists()) return
        val entry = runCatching { RetroArchCfgParser.parse(target.readText(), fileName = target.name) }.getOrNull()
        val prunable = entry != null && entry.provenance == null && !entry.isUserOwned && target.name in bundledNames
        if (prunable) target.delete() else park(dir, target)
    }

    private fun rewrite(text: String): String =
        text.lineSequence()
            .filterNot { it.trimStart().startsWith("cannoli_user") }
            .filterNot { it.trimStart().startsWith("cannoli_descriptor") }
            .plus("cannoli_source = \"USER\"")
            .joinToString("\n") + "\n"

    private fun park(dir: File, file: File) {
        val parked = File(dir, "parked").apply { mkdirs() }
        var dest = File(parked, file.name)
        var n = 1
        while (dest.exists()) {
            dest = File(parked, "${file.nameWithoutExtension}_${n++}.${file.extension}")
        }
        if (!file.renameTo(dest)) {
            throw IOException("Failed to park ${file.name}")
        }
    }

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(text.toByteArray())
                fos.fd.sync()
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Failed to rename migrated cfg ${file.name}")
        }
    }

    // Ids used to end in six hex characters of the descriptor hash. Per-model scoping drops it.
    private fun String.dropDescriptorSuffix(): String =
        replace(Regex("_[0-9a-f]{6}$"), "")

    companion object {
        private const val STAMP_FILE = ".migration_complete"
    }
}
