package dev.cannoli.scorza.model

import java.io.File

data class Collection(
    val stem: String,
    val file: File
) {
    val displayName: String get() = stemToDisplayName(stem)

    companion object {
        fun stemToDisplayName(stem: String): String {
            val idx = stem.lastIndexOf('_')
            if (idx < 0) return stem
            val suffix = stem.substring(idx + 1)
            return if (suffix.length == 4 && suffix.all { it in '0'..'9' || it in 'a'..'f' }) {
                stem.substring(0, idx)
            } else {
                stem
            }
        }

    }
}
