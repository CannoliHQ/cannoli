package dev.cannoli.scorza.ra

import java.io.File

object RaOfflineLookup {
    fun bodyFor(dir: File?, postData: String?): String? {
        if (dir == null || postData == null) return null
        return when {
            postData.contains("r=login2") -> read(File(dir, "login2.json"))
            postData.contains("r=achievementsets") -> {
                val id = intParam(postData, "g") ?: param(postData, "m")?.let { hashToId(dir, it) }
                id?.let { read(File(dir, "$it/achievementsets.json")) }
            }
            postData.contains("r=startsession") ->
                intParam(postData, "g")?.let { read(File(dir, "$it/startsession.json")) }
            else -> null
        }
    }

    private fun param(postData: String, key: String): String? =
        Regex("(?:^|&)$key=([^&]+)").find(postData)?.groupValues?.get(1)

    /** Game-id params must be plain integers; rejecting anything else keeps a crafted `g=../x`
     *  value from escaping the cache directory when building the file path. */
    private fun intParam(postData: String, key: String): String? =
        param(postData, key)?.takeIf { it.toIntOrNull() != null }

    private fun hashToId(dir: File, hash: String): String? {
        val dirs = dir.listFiles { f -> f.isDirectory } ?: return null
        for (g in dirs) {
            if (g.name.toIntOrNull() == null) continue
            val hf = File(g, "hash")
            if (hf.exists() && runCatching { hf.readText().trim() }.getOrNull() == hash) return g.name
        }
        return null
    }

    private fun read(f: File): String? =
        if (f.exists()) try { f.readText() } catch (_: Exception) { null } else null
}
