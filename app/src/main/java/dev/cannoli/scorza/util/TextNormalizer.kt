package dev.cannoli.scorza.util

import java.text.Normalizer
import java.util.Locale

object TextNormalizer {
    private val combiningMarks = Regex("\\p{Mn}+")

    fun normalize(text: String): String =
        combiningMarks
            .replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
            .lowercase(Locale.ROOT)
            .trim()
}
