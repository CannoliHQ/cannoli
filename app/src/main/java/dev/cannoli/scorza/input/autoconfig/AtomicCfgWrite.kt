package dev.cannoli.scorza.input.autoconfig

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// Shared by every writer of a cfg into the autoconfig directory (AutoconfigRepository.save,
// AutoconfigSeeder, AutoconfigMigration): write to a tmp sibling, fsync, then rename over the
// target so a reader never observes a partially written file. Any failure, during the write or
// the rename, deletes the tmp file rather than leaving a stray .tmp behind.
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
