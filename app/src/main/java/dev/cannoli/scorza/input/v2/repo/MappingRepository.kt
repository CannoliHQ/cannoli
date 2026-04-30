package dev.cannoli.scorza.input.v2.repo

import dev.cannoli.scorza.input.v2.DeviceTemplate
import java.io.File
import java.io.IOException

class TemplateRepository(private val templatesDir: File) {

    fun list(): List<DeviceTemplate> {
        if (!templatesDir.exists()) return emptyList()
        return templatesDir.listFiles { f -> f.isFile && f.extension.equals("ini", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching {
                    TemplateIniSerializer.fromIni(
                        id = file.nameWithoutExtension,
                        ini = file.readText(),
                    )
                }.getOrNull()
            }
            ?: emptyList()
    }

    fun findById(id: String): DeviceTemplate? {
        val file = File(templatesDir, "$id.ini")
        if (!file.exists()) return null
        return runCatching {
            TemplateIniSerializer.fromIni(id, file.readText())
        }.getOrNull()
    }

    fun save(template: DeviceTemplate) {
        templatesDir.mkdirs()
        val file = File(templatesDir, "${template.id}.ini")
        val tmp = File(templatesDir, "${template.id}.ini.tmp")
        java.io.FileOutputStream(tmp).use { fos ->
            fos.write(TemplateIniSerializer.toIni(template).toByteArray())
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Failed to rename template tmp file for ${template.id}")
        }
    }

    fun delete(id: String) {
        File(templatesDir, "$id.ini").delete()
    }
}
