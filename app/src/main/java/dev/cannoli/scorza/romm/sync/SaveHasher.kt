package dev.cannoli.scorza.romm.sync

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

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

    fun hashBundle(entries: Map<String, File>): String =
        combine(entries.mapValues { (_, file) -> hashFile(file) })

    fun hashZipContents(zip: File): String = ZipFile(zip).use { zf ->
        combine(
            zf.entries().asSequence().filterNot { it.isDirectory }.associate { entry ->
                entry.name to zf.getInputStream(entry).use { md5Hex(it.readBytes()) }
            }
        )
    }

    fun isZip(file: File): Boolean = file.inputStream().use { ins ->
        val sig = ByteArray(4)
        ins.read(sig) == 4 && sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte()
    }

    private fun combine(hashes: Map<String, String>): String =
        md5Hex(hashes.keys.sorted().joinToString("\n") { "$it:${hashes.getValue(it)}" }.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
