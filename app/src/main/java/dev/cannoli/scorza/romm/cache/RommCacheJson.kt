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
    val box2d: String? = null,
    val box3d: String? = null,
    val mix: String? = null,
    val title: String? = null,
    val screenshot: String? = null,
    val marquee: String? = null,
    val manual: String? = null,
)

object RommCacheJson {
    private val fileListSerializer = ListSerializer(CachedFile.serializer())
    private val stringListSerializer = ListSerializer(String.serializer())

    fun encodeSsMedia(media: RommSsMedia?): String =
        rommJson.encodeToString(
            CachedSsMedia.serializer(),
            media?.let { CachedSsMedia(it.box2d, it.box3d, it.mix, it.titleScreen, it.screenshot, it.marquee, it.manual) }
                ?: CachedSsMedia(),
        )

    fun decodeSsMedia(json: String): RommSsMedia? {
        val c = rommJson.decodeFromString(CachedSsMedia.serializer(), json)
        if (c.box2d == null && c.box3d == null && c.mix == null && c.title == null &&
            c.screenshot == null && c.marquee == null && c.manual == null
        ) return null
        return RommSsMedia(c.box2d, c.box3d, c.mix, c.title, c.screenshot, c.marquee, c.manual)
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
