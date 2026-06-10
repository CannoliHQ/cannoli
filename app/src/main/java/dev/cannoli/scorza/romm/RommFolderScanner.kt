package dev.cannoli.scorza.romm

import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RommFolderScanner(private val romsDirProvider: () -> File) {
    private val cache = ConcurrentHashMap<String, List<LocalFile>>()

    fun localFiles(tag: String): List<LocalFile> = cache.getOrPut(tag) { scan(tag) }

    fun invalidate() = cache.clear()

    private fun scan(tag: String): List<LocalFile> {
        val roms = romsDirProvider()
        val dir = File(roms, tag).takeIf { it.exists() }
            ?: roms.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(tag, ignoreCase = true) }
            ?: return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.map { LocalFile(it.name, it.length()) } ?: emptyList()
    }
}
