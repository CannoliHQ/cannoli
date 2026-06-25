package dev.cannoli.scorza.ra

import org.json.JSONObject

object RaSetMetadata {
    data class Parsed(val title: String, val count: Int, val points: Int)

    fun parse(body: String): Parsed? {
        val obj = try { JSONObject(body) } catch (_: Exception) { return null }
        if (!obj.optBoolean("Success", false)) return null
        val title = obj.optString("Title", "")
        var count = 0
        var points = 0
        val sets = obj.optJSONArray("Sets")
        if (sets != null) {
            for (i in 0 until sets.length()) {
                val achs = sets.getJSONObject(i).optJSONArray("Achievements") ?: continue
                for (j in 0 until achs.length()) {
                    count++
                    points += achs.getJSONObject(j).optInt("Points", 0)
                }
            }
        }
        return Parsed(title, count, points)
    }
}
