package dev.cannoli.scorza.scanner

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CachedPlatformEntry(val lastModified: Long, val hasGames: Boolean)
data class CachedGameEntry(val path: String, val displayName: String, val artName: String, val isSubfolder: Boolean, val discPaths: List<String>)
data class CachedGameList(val dirLastModified: Long, val games: List<CachedGameEntry>)

class ScanCache(private val cannoliRoot: File) {

    private val configDir = File(cannoliRoot, "Config")
    private val platformCacheFile = File(configDir, ".platform_cache.json")
    private val gameCacheDir = File(configDir, ".game_cache")

    fun loadPlatformCache(): Map<String, CachedPlatformEntry>? {
        if (!platformCacheFile.exists()) return null
        return try {
            val json = JSONObject(platformCacheFile.readText())
            val result = mutableMapOf<String, CachedPlatformEntry>()
            for (tag in json.keys()) {
                val obj = json.getJSONObject(tag)
                result[tag] = CachedPlatformEntry(obj.getLong("lastModified"), obj.getBoolean("hasGames"))
            }
            result
        } catch (_: Exception) { null }
    }

    fun savePlatformCache(entries: Map<String, CachedPlatformEntry>) {
        val json = JSONObject()
        for ((tag, entry) in entries) {
            json.put(tag, JSONObject().apply {
                put("lastModified", entry.lastModified)
                put("hasGames", entry.hasGames)
            })
        }
        writeAtomic(platformCacheFile, json.toString())
    }

    fun loadGameCache(tag: String): CachedGameList? {
        val file = File(gameCacheDir, "$tag.json")
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val dirMod = json.getLong("lastModified")
            val arr = json.getJSONArray("games")
            val games = (0 until arr.length()).map { i ->
                val g = arr.getJSONObject(i)
                val discArr = g.optJSONArray("discPaths")
                val discPaths = if (discArr != null) (0 until discArr.length()).map { discArr.getString(it) } else emptyList()
                CachedGameEntry(g.getString("path"), g.getString("displayName"), g.optString("artName", g.getString("displayName")), g.getBoolean("isSubfolder"), discPaths)
            }
            CachedGameList(dirMod, games)
        } catch (_: Exception) { null }
    }

    fun saveGameCache(tag: String, dirLastModified: Long, games: List<CachedGameEntry>) {
        val json = JSONObject()
        json.put("lastModified", dirLastModified)
        val arr = JSONArray()
        for (g in games) {
            arr.put(JSONObject().apply {
                put("path", g.path)
                put("displayName", g.displayName)
                put("artName", g.artName)
                put("isSubfolder", g.isSubfolder)
                if (g.discPaths.isNotEmpty()) {
                    put("discPaths", JSONArray(g.discPaths))
                }
            })
        }
        json.put("games", arr)
        gameCacheDir.mkdirs()
        writeAtomic(File(gameCacheDir, "$tag.json"), json.toString())
    }

    fun invalidatePlatform(tag: String) {
        File(gameCacheDir, "$tag.json").delete()
    }

    fun invalidateAll() {
        platformCacheFile.delete()
        if (gameCacheDir.exists()) {
            gameCacheDir.listFiles()?.forEach { it.delete() }
            gameCacheDir.delete()
        }
    }

    private fun writeAtomic(dest: File, content: String) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        try {
            tmp.writeText(content)
            tmp.renameTo(dest)
        } catch (_: Exception) {
            tmp.delete()
        }
    }
}
