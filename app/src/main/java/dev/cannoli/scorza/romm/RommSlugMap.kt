package dev.cannoli.scorza.romm

import android.content.res.AssetManager
import kotlinx.serialization.decodeFromString

class RommSlugMap private constructor(private val slugToTag: Map<String, String>) {

    fun tagForSlug(slug: String): String? = slugToTag[slug.lowercase()]

    companion object {
        fun parse(json: String): RommSlugMap =
            RommSlugMap(rommJson.decodeFromString<Map<String, String>>(json).mapKeys { it.key.lowercase() })

        fun load(assets: AssetManager): RommSlugMap =
            parse(assets.open("romm_platforms.json").use { it.bufferedReader().readText() })
    }
}
