package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommSsMedia
import dev.cannoli.scorza.romm.rommJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
data class CachedFile(
    val name: String,
    val size: Long,
    val crc: String? = null,
    val md5: String? = null,
    val sha1: String? = null,
)

@Serializable
data class CachedSsMedia(
    val box3d: String? = null,
    val mix: String? = null,
    val title: String? = null,
    val marquee: String? = null,
)

object RommCacheJson {
    private val fileListSerializer = ListSerializer(CachedFile.serializer())
    private val stringListSerializer = ListSerializer(String.serializer())

    fun encodeSsMedia(media: RommSsMedia?): String =
        rommJson.encodeToString(
            CachedSsMedia.serializer(),
            media?.let { CachedSsMedia(it.box3dPath, it.mixPath, it.titleScreenPath, it.marqueePath) }
                ?: CachedSsMedia(),
        )

    fun decodeSsMedia(json: String): RommSsMedia? {
        val c = rommJson.decodeFromString(CachedSsMedia.serializer(), json)
        if (c.box3d == null && c.mix == null && c.title == null && c.marquee == null) return null
        return RommSsMedia(c.box3d, c.mix, c.title, c.marquee)
    }

    fun encodeFiles(files: List<RommFile>): String =
        rommJson.encodeToString(fileListSerializer, files.map { CachedFile(it.fileName, it.sizeBytes, it.crc, it.md5, it.sha1) })

    fun decodeFiles(json: String): List<RommFile> =
        rommJson.decodeFromString(fileListSerializer, json).map { RommFile(it.name, it.size, it.crc, it.md5, it.sha1) }

    fun encodeStrings(values: List<String>): String =
        rommJson.encodeToString(stringListSerializer, values)

    fun decodeStrings(json: String): List<String> =
        rommJson.decodeFromString(stringListSerializer, json)
}
