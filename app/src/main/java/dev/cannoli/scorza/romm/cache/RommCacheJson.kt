package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.RommFile
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

object RommCacheJson {
    private val fileListSerializer = ListSerializer(CachedFile.serializer())
    private val stringListSerializer = ListSerializer(String.serializer())

    fun encodeFiles(files: List<RommFile>): String =
        rommJson.encodeToString(fileListSerializer, files.map { CachedFile(it.fileName, it.sizeBytes, it.crc, it.md5, it.sha1) })

    fun decodeFiles(json: String): List<RommFile> =
        rommJson.decodeFromString(fileListSerializer, json).map { RommFile(it.name, it.size, it.crc, it.md5, it.sha1) }

    fun encodeStrings(values: List<String>): String =
        rommJson.encodeToString(stringListSerializer, values)

    fun decodeStrings(json: String): List<String> =
        rommJson.decodeFromString(stringListSerializer, json)
}
