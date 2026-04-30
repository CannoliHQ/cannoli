package dev.cannoli.scorza.input.v2.repo

import dev.cannoli.scorza.input.v2.DeviceMapping
import java.io.File
import java.io.IOException

class MappingRepository(private val mappingsDir: File) {

    fun list(): List<DeviceMapping> {
        if (!mappingsDir.exists()) return emptyList()
        return mappingsDir.listFiles { f -> f.isFile && f.extension.equals("ini", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching {
                    MappingIniSerializer.fromIni(
                        id = file.nameWithoutExtension,
                        ini = file.readText(),
                    )
                }.getOrNull()
            }
            ?: emptyList()
    }

    fun findById(id: String): DeviceMapping? {
        val file = File(mappingsDir, "$id.ini")
        if (!file.exists()) return null
        return runCatching {
            MappingIniSerializer.fromIni(id, file.readText())
        }.getOrNull()
    }

    fun save(mapping: DeviceMapping) {
        mappingsDir.mkdirs()
        val file = File(mappingsDir, "${mapping.id}.ini")
        val tmp = File(mappingsDir, "${mapping.id}.ini.tmp")
        java.io.FileOutputStream(tmp).use { fos ->
            fos.write(MappingIniSerializer.toIni(mapping).toByteArray())
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Failed to rename mapping tmp file for ${mapping.id}")
        }
    }

    fun delete(id: String) {
        File(mappingsDir, "$id.ini").delete()
    }
}
