package dev.cannoli.scorza.input

import android.content.Context
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads `assets/controller_blacklist.json` and answers whether a device should be ignored as a
 * controller. Used by both the legacy [ControllerManager] (gameplay port assignment) and the
 * v2 [dev.cannoli.scorza.input.v2.runtime.ControllerV2Bridge] (identity / port routing) so the
 * two stay in sync.
 */
@Singleton
class ControllerBlacklist @Inject constructor() {
    private var vendors: Set<Int> = emptySet()
    private var namePrefixes: List<String> = emptyList()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        try {
            val json = JSONObject(context.assets.open("controller_blacklist.json").bufferedReader().readText())
            json.optJSONArray("vendors")?.let { arr ->
                vendors = (0 until arr.length()).map { arr.getJSONObject(it).getInt("id") }.toSet()
            }
            json.optJSONArray("name_prefixes")?.let { arr ->
                namePrefixes = (0 until arr.length()).map { arr.getString(it) }
            }
            loaded = true
        } catch (_: Exception) {
        }
    }

    fun isBlocked(deviceName: String?, vendorId: Int): Boolean {
        if (vendorId != 0 && vendorId in vendors) return true
        val name = deviceName ?: return false
        return namePrefixes.any { name.startsWith(it, ignoreCase = true) }
    }
}
