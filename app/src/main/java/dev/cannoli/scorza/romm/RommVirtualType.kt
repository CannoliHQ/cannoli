package dev.cannoli.scorza.romm

import dev.cannoli.ui.R

enum class RommVirtualType(val serverValue: String, val labelRes: Int) {
    FRANCHISE("franchise", R.string.romm_vtype_franchise),
    COLLECTION("collection", R.string.romm_vtype_collection),
    GENRE("genre", R.string.romm_vtype_genre),
    COMPANY("company", R.string.romm_vtype_company),
    MODE("mode", R.string.romm_vtype_mode);

    companion object {
        fun from(serverValue: String?): RommVirtualType? =
            entries.firstOrNull { it.serverValue == serverValue }

        fun orderedFrom(serverValues: Collection<String>): List<String> {
            val present = serverValues.toSet()
            val known = entries.map { it.serverValue }.filter { it in present }
            val unknown = present.filter { from(it) == null }.sorted()
            return known + unknown
        }
    }
}
