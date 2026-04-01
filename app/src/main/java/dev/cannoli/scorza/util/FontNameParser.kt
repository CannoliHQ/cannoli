package dev.cannoli.scorza.util

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FontNameParser {

    fun getFamilyName(file: File): String? = try {
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(12)
            raf.readFully(buf)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
            val sfVersion = bb.int
            if (sfVersion != 0x00010000 && sfVersion != 0x4F54544F) return null
            val numTables = bb.short.toInt() and 0xFFFF
            raf.seek(12)
            var nameOffset = -1L
            for (i in 0 until numTables) {
                val tag = ByteArray(4)
                raf.readFully(tag)
                raf.skipBytes(4)
                val offset = raf.readInt().toLong() and 0xFFFFFFFFL
                raf.skipBytes(4)
                if (String(tag) == "name") { nameOffset = offset; break }
            }
            if (nameOffset < 0) return null
            raf.seek(nameOffset)
            @Suppress("UNUSED_VARIABLE") val format = raf.readUnsignedShort()
            val count = raf.readUnsignedShort()
            val stringOffset = raf.readUnsignedShort()
            val storageBase = nameOffset + stringOffset
            for (i in 0 until count) {
                val platformID = raf.readUnsignedShort()
                val encodingID = raf.readUnsignedShort()
                val languageID = raf.readUnsignedShort()
                val nameID = raf.readUnsignedShort()
                val length = raf.readUnsignedShort()
                val strOffset = raf.readUnsignedShort()
                if (nameID == 1) {
                    val pos = raf.filePointer
                    raf.seek(storageBase + strOffset)
                    val data = ByteArray(length)
                    raf.readFully(data)
                    raf.seek(pos)
                    val charset = if (platformID == 3 || platformID == 0) "UTF-16BE" else "UTF-8"
                    val name = String(data, charset(charset)).trim()
                    if (name.isNotEmpty()) return name
                }
            }
            null
        }
    } catch (_: Exception) {
        null
    }
}
