package dev.cannoli.scorza.i18n

// RetroArch picks its own language: user_language defaults to frontend_driver_get_user_language(),
// which on Android reads the device locale. Cannoli's language is chosen independently, so without
// this the Everything settings menu comes out in the device's language while the rest of the
// launcher is in the user's. Values are enum retro_language from libretro.h.
object RetroArchLanguage {

    private val byTag = mapOf(
        "en" to 0,
        "ja" to 1,
        "fr" to 2,
        "es" to 3,
        "de" to 4,
        "it" to 5,
        "nl" to 6,
        "pt-br" to 7,
        "pt-pt" to 8,
        "pt" to 8,
        "ru" to 9,
        "ko" to 10,
        "zh-tw" to 11,
        "zh-hant" to 11,
        "zh-cn" to 12,
        "zh-hans" to 12,
        "zh" to 12,
        "pl" to 14,
        "vi" to 15,
        "ar" to 16,
        "el" to 17,
        "tr" to 18,
        "sk" to 19,
        "fa" to 20,
        "he" to 21,
        "fi" to 23,
        "id" to 24,
        "sv" to 25,
        "uk" to 26,
        "cs" to 27,
        "ca" to 29,
        "hu" to 31,
        "be" to 32,
        "gl" to 33,
        "nb" to 34,
        "no" to 34,
        "ga" to 35,
        "th" to 36,
    )

    /**
     * The retro_language for a BCP-47 tag, or null to leave RetroArch detecting it from the device.
     * Null covers both "no override" and a language RetroArch does not ship, since pinning either
     * to English would be worse than letting RetroArch choose.
     */
    fun forTag(tag: String): Int? {
        val normalized = tag.trim().lowercase().replace('_', '-')
        if (normalized.isEmpty()) return null
        byTag[normalized]?.let { return it }
        // es-419 and friends: the region carries no RetroArch distinction, so fall back to the
        // base language rather than to English.
        val base = normalized.substringBefore('-')
        return byTag[base]
    }
}
