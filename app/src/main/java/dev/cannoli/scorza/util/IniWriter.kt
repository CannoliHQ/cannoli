package dev.cannoli.scorza.util

import java.io.File

object IniWriter {

    fun write(file: File, sections: Map<String, Map<String, String>>) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        for ((section, entries) in sections) {
            if (entries.isEmpty()) continue
            sb.appendLine("[$section]")
            for ((key, value) in entries) {
                sb.appendLine("$key=$value")
            }
            sb.appendLine()
        }
        writeAtomic(file, sb.toString())
    }

    fun mergeWrite(file: File, section: String, entries: Map<String, String>) {
        val existing = if (file.exists()) IniParser.parse(file) else IniData(emptyMap())
        val merged = existing.sections.toMutableMap()
        val sectionMap = (merged[section] ?: emptyMap()).toMutableMap()
        sectionMap.putAll(entries)
        merged[section] = sectionMap
        write(file, merged)
    }

    private fun writeAtomic(dest: File, content: String) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        try {
            java.io.FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray())
                fos.fd.sync()
            }
            tmp.renameTo(dest)
        } catch (_: Exception) {
            tmp.delete()
        }
    }
}
