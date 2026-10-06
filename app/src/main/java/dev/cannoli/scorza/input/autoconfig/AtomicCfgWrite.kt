package dev.cannoli.scorza.input.autoconfig

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal fun writeCfgAtomic(file: File, text: String) {
    val tmp = File(file.parentFile, "${file.name}.tmp")
    try {
        FileOutputStream(tmp).use { fos ->
            fos.write(text.toByteArray())
            fos.fd.sync()
        }
    } catch (e: IOException) {
        tmp.delete()
        throw e
    }
    if (!tmp.renameTo(file)) {
        tmp.delete()
        throw IOException("Failed to atomically write ${file.name}")
    }
}
