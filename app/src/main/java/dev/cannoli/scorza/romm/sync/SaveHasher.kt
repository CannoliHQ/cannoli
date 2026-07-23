package dev.cannoli.scorza.romm.sync

import java.io.File
import java.security.MessageDigest

object SaveHasher {
    const val EMPTY_MD5 = "d41d8cd98f00b204e9800998ecf8427e"

    fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).toHex()

    fun hashFile(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    fun hashBundle(entries: Map<String, File>): String {
        val combined = entries.keys.sorted()
            .joinToString("\n") { name -> "$name:${hashFile(entries.getValue(name))}" }
        return md5Hex(combined.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
