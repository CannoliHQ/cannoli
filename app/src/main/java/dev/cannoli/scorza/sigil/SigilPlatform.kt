package dev.cannoli.scorza.sigil

/**
 * The platforms sigil extracts that Cannoli also has a tag for. Ordinals are the wire format the
 * JNI shim switches on, so entries are appended, never reordered.
 *
 * Sigil also reads Switch, PS3, Xbox and Xbox 360. Switch needs a header key out of prod.keys that
 * Cannoli has nowhere to store and no way to ask for; PS3 wants a game folder or a PARAM.SFO while
 * Cannoli's PS3 rows are ISOs for aps3e; and neither Xbox has a Cannoli tag.
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
