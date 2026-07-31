package dev.cannoli.scorza.i18n

import android.graphics.Paint
import android.graphics.Typeface

fun fontPromptNeeded(coverageSample: String?, fontCovers: Boolean): Boolean =
    coverageSample != null && !fontCovers

object FontCoverage {
    fun covers(typeface: Typeface, sample: String): Boolean {
        val paint = Paint().apply { setTypeface(typeface) }
        var i = 0
        while (i < sample.length) {
            val cp = sample.codePointAt(i)
            if (!paint.hasGlyph(String(Character.toChars(cp)))) return false
            i += Character.charCount(cp)
        }
        return true
    }
}
