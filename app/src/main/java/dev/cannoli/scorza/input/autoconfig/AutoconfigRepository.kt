package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.DeviceMapping
import java.io.File

class AutoconfigRepository(
    private val debugBuild: Boolean = false,
    private val stagingDirProvider: () -> File? = { null },
    private val dirProvider: () -> File?,
) {

    // Null before first run's storage step. A pad connecting during the controller step recomputes
    // the controller list, which lands here well before anyone has said where Cannoli lives.
    private val dir: File? get() = dirProvider()

    // App-private storage, so it needs no permission and is writable before the storage step has
    // run at all. Where a cfg lives until first run says which card it belongs on.
    private val stagingDir: File? get() = stagingDirProvider()

    private val activeDir: File? get() = dir ?: stagingDir

    @Volatile private var cache: List<RetroArchCfgEntry>? = null

    // Sorted because consumers pick the first entry that matches a pad and a directory listing has
    // no order of its own: ext4 and exFAT hand back the same cfgs in different sequences, so two
    // equally good matches would otherwise decide a pad's layout by which card it booted from.
    fun listEntries(): List<RetroArchCfgEntry> {
        cache?.let { return it }
        // Deliberately not cached: the root resolves later, and an empty listing must not stick.
        val dir = dir ?: return emptyList()
        val loaded = dir.listFiles { f -> f.isFile && f.extension.equals("cfg", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching { RetroArchCfgParser.parse(file.readText(), fileName = file.name) }.getOrNull()
            }
            ?.sortedBy { it.fileName }
            ?: emptyList()
        cache = loaded
        return loaded
    }

    fun findById(id: String): RetroArchCfgEntry? =
        (listEntries() + stagedEntries()).firstOrNull { it.fileName == "$id.cfg" }

    /**
     * Cfgs written before first run chose a card, which only [save] produces.
     *
     * Kept apart from [listEntries] rather than folded into it. The resolver treats a non-empty
     * disk set as the seeded database and stops consulting the shipped assets, which is what keeps
     * profiles pinned to other handhelds from being resurrected. A staged cfg is one pad the user
     * just configured, not a seeded database, and must not silence the assets for every other pad.
     */
    fun stagedEntries(): List<RetroArchCfgEntry> {
        val dir = stagingDir?.takeIf { it.isDirectory } ?: return emptyList()
        return dir.listFiles { f: File -> f.isFile && f.extension.equals("cfg", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching { RetroArchCfgParser.parse(file.readText(), fileName = file.name) }.getOrNull()
            }
            ?.sortedBy { it.fileName }
            ?: emptyList()
    }

    fun save(mapping: DeviceMapping) {
        // Staging catches a write that lands before first run has chosen a card, so a wizard
        // finished during onboarding survives a process death instead of living in memory. It is
        // moved onto the card by [promoteStaging].
        val dir = activeDir ?: error("autoconfig save with neither a chosen root nor a staging dir")
        dir.mkdirs()
        val file = File(dir, "${mapping.id}.cfg")
        writeCfgAtomic(file, RetroArchCfgWriter.write(mapping, debugBuild))
        invalidate()
    }

    fun delete(id: String) {
        val dir = activeDir ?: return
        File(dir, "$id.cfg").delete()
        invalidate()
    }

    /**
     * Move anything written before first run chose a card onto that card.
     *
     * Only [save] writes into staging, so everything there is the user's own work and wins a name
     * that already exists on the card. Staging is emptied rather than left in place: it is only
     * read while the root is unresolved, and a root that becomes unreadable later would otherwise
     * start serving stale copies of cfgs that have since moved on.
     */
    fun promoteStaging() {
        val target = dir ?: return
        val source = stagingDir?.takeIf { it.isDirectory } ?: return
        val staged = source.listFiles { f: File -> f.isFile && f.extension.equals("cfg", true) }
            ?: return
        if (staged.isNotEmpty()) target.mkdirs()
        for (file in staged) {
            // A file that cannot be moved is left where it is, so the next promotion retries it
            // rather than losing the only copy.
            runCatching { writeCfgAtomic(File(target, file.name), file.readText()) }
                .onSuccess { file.delete() }
        }
        source.delete()
        invalidate()
    }

    fun invalidate() {
        cache = null
    }
}