package dev.cannoli.core.achievements

import java.io.File
import java.net.URLDecoder

/**
 * Answers a RetroAchievements request from the offline cache, or does not answer it.
 *
 * The three request types here are the three the preloader stores. Anything else returns null and
 * the caller decides what that means, because a lookup that invented a body would be telling the
 * achievement client something no server ever said.
 */
class RaOfflineLookup(private val dir: File) {

    fun requestType(postData: String): String? = field(postData, "r")

    fun bodyFor(postData: String): String? = when (requestType(postData)) {
        "login2" -> File(dir, "login2.json").readIfPresent()
        "achievementsets" -> gameDir(postData)?.let { File(it, "achievementsets.json").readIfPresent() }
        "startsession" -> gameDir(postData)?.let { File(it, "startsession.json").readIfPresent() }
        else -> null
    }

    /** Which game this request is about, or null when nothing in it says. */
    fun gameIdFor(postData: String): Int? = gameDir(postData)?.name?.toIntOrNull()

    /**
     * The cached directory this request is about, by game id, or by ROM hash when the client has no
     * id to send. The hash fallback is what lets a game cached under one id still get a session when
     * the launcher never stamped that id onto the ROM row.
     */
    private fun gameDir(postData: String): File? {
        val id = field(postData, "g")?.toIntOrNull()
        if (id != null && id > 0) return File(dir, id.toString()).takeIf { it.isDirectory }
        val hash = field(postData, "m")?.lowercase()?.takeIf { it.isNotEmpty() && it.all { c -> c in HEX } }
            ?: return null
        return dir.listFiles()
            ?.firstOrNull { it.isDirectory && (it.name.toIntOrNull() ?: 0) > 0 && hash in RaOfflineStore.hashesIn(it) }
    }

    // Blank reads as absent: a cache file that is empty or whitespace-only has no answer to give,
    // and handing it back would serve a zero-length 200 as though it were a real response.
    private fun File.readIfPresent(): String? = try {
        if (isFile) readText().takeIf { it.isNotBlank() } else null
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val HEX = "0123456789abcdef"

        /** One form field by name, decoded, or null when the body does not carry it. */
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
