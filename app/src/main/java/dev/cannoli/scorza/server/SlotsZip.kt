package dev.cannoli.scorza.server

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SlotsZip {

    fun build(gameDir: File, romName: String): ByteArray? {
        if (!gameDir.isDirectory) return null
        val files = mutableListOf<File>()
        for (slot in 0..10) {
            val stateName = when (slot) {
                0 -> "$romName.state.auto"
                1 -> "$romName.state"
                else -> "$romName.state${slot - 1}"
            }
            val stateFile = File(gameDir, stateName)
            if (!stateFile.isFile) continue
            files.add(stateFile)
            val thumb = File(gameDir, "$stateName.png")
            if (thumb.isFile) files.add(thumb)
        }
        if (files.isEmpty()) return null

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val buf = ByteArray(65536)
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        zip.write(buf, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
