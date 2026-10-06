package dev.cannoli.core.achievements

import java.io.File
import java.net.URLDecoder

class RaOfflineLookup(private val dir: File) {

    fun requestType(postData: String): String? = field(postData, "r")

    fun bodyFor(postData: String): String? = when (requestType(postData)) {
        "login2" -> File(dir, "login2.json").readIfPresent()
        "achievementsets" -> gameDir(postData)?.let { File(it, "achievementsets.json").readIfPresent() }
        "startsession" -> gameDir(postData)?.let { File(it, "startsession.json").readIfPresent() }
        else -> null
    }

    fun gameIdFor(postData: String): Int? = gameDir(postData)?.name?.toIntOrNull()

    private fun gameDir(postData: String): File? {
        val id = field(postData, "g")?.toIntOrNull()
        if (id != null && id > 0) return File(dir, id.toString()).takeIf { it.isDirectory }
        val hash = field(postData, "m")?.lowercase()?.takeIf { it.isNotEmpty() && it.all { c -> c in HEX } }
            ?: return null
        return dir.listFiles()
            ?.firstOrNull { it.isDirectory && (it.name.toIntOrNull() ?: 0) > 0 && hash in RaOfflineStore.hashesIn(it) }
    }

    private fun File.readIfPresent(): String? = try {
        if (isFile) readText().takeIf { it.isNotBlank() } else null
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val HEX = "0123456789abcdef"

        fun field(postData: String, key: String): String? {
            for (pair in postData.split('&')) {
                val i = pair.indexOf('=')
                if (i <= 0) continue
                if (pair.substring(0, i) != key) continue
                val raw = pair.substring(i + 1)
                if (raw.isEmpty()) return null
                return try {
                    URLDecoder.decode(raw, "UTF-8")
                } catch (_: Exception) {
                    null
                }
            }
            return null
        }
    }
}
