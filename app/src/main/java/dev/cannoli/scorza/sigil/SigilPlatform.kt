package dev.cannoli.scorza.sigil

/**
 * The platforms sigil extracts that Cannoli also has a tag for. Ordinals are the wire format the
 * JNI shim switches on, so entries are appended, never reordered.
 */
enum class SigilPlatform {
    PSX,
    PS2,
    PSP,
    PSVITA,
    GAMECUBE,
    WII,
    WIIU,
    THREEDS,
    DREAMCAST,
    ;

    companion object {
        private val BY_TAG = mapOf(
            "PS" to PSX,
            "PS2" to PS2,
            "PSP" to PSP,
            "PSVITA" to PSVITA,
            "GC" to GAMECUBE,
            "WII" to WII,
            "WIIU" to WIIU,
            "3DS" to THREEDS,
            "DC" to DREAMCAST,
        )

        val TAGS: Set<String> = BY_TAG.keys

        fun fromTag(tag: String): SigilPlatform? = BY_TAG[tag.uppercase()]
    }
}
