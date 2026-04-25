package dev.cannoli.scorza.db.importer.legacy

import java.io.File
import java.io.IOException

class RecentlyPlayedManager(cannoliRoot: File) {
    private val stateDir = File(cannoliRoot, "Config/State")
    private val recentlyPlayedFile = File(stateDir, "recently_played.txt")
    private val lock = Any()

    fun record(romPath: String) {
        synchronized(lock) {
            stateDir.mkdirs()
            val existing = try {
                if (recentlyPlayedFile.exists()) recentlyPlayedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                else emptyList()
            } catch (_: IOException) { emptyList() }
            val updated = listOf(romPath) + existing.filter { it != romPath }
            recentlyPlayedFile.writeText(updated.take(10).joinToString("\n") + "\n")
        }
    }

    fun load(): List<String> {
        synchronized(lock) {
            if (!recentlyPlayedFile.exists()) return emptyList()
            return try {
                recentlyPlayedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.take(10)
            } catch (_: IOException) { emptyList() }
        }
    }

    fun renamePath(oldPath: String, newPath: String) {
        if (oldPath == newPath) return
        synchronized(lock) {
            if (!recentlyPlayedFile.exists()) return
            val existing = try {
                recentlyPlayedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            } catch (_: IOException) { return }
            if (existing.none { it == oldPath }) return
            val updated = existing.map { if (it == oldPath) newPath else it }
                .distinct()
            recentlyPlayedFile.writeText(updated.joinToString("\n") + "\n")
        }
    }

    fun remove(romPath: String) {
        synchronized(lock) {
            if (!recentlyPlayedFile.exists()) return
            val remaining = try {
                recentlyPlayedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && it != romPath }
            } catch (_: IOException) { return }
            if (remaining.isEmpty()) recentlyPlayedFile.delete()
            else recentlyPlayedFile.writeText(remaining.joinToString("\n") + "\n")
        }
    }

    fun clear() {
        synchronized(lock) {
            if (recentlyPlayedFile.exists()) recentlyPlayedFile.delete()
        }
    }

    fun hasAny(): Boolean {
        synchronized(lock) {
            if (!recentlyPlayedFile.exists()) return false
            return try {
                recentlyPlayedFile.readLines().any { it.trim().isNotEmpty() }
            } catch (_: IOException) { false }
        }
    }
}
