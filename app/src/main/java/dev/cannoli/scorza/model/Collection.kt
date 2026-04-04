package dev.cannoli.scorza.model

import java.io.File

data class Collection(
    val stem: String,
    val file: File
) {
    val displayName: String
        get() {
            val idx = stem.lastIndexOf('_')
            if (idx < 0) return stem
            val suffix = stem.substring(idx + 1)
            return if (suffix.length == 4 && suffix.all { it in '0'..'9' || it in 'a'..'f' }) {
                stem.substring(0, idx)
            } else {
                stem
            }
        }

    companion object {
        private val hexChars = "0123456789abcdef"

        fun generateHash(): String {
            return (1..4).map { hexChars.random() }.joinToString("")
        }

        fun generateUniqueHash(existingStems: Set<String>, name: String): String {
            var hash = generateHash()
            while ("${name}_$hash" in existingStems) {
                hash = generateHash()
            }
            return hash
        }

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

        fun childDisplayName(childStem: String, parentStem: String): String {
            val childName = stemToDisplayName(childStem)
            val parentName = stemToDisplayName(parentStem)
            val prefixWithHyphen = "$parentName - "
            return if (childName.startsWith(prefixWithHyphen)) {
                childName.removePrefix(prefixWithHyphen)
            } else {
                childName
            }
        }
    }
}
