package dev.cannoli.core

import java.io.File

data class IniData(
    val sections: Map<String, Map<String, String>>
) {
    fun get(section: String, key: String): String? = sections[section]?.get(key)

    fun getSection(section: String): Map<String, String> = sections[section] ?: emptyMap()
}

object IniParser {

    fun parse(file: File): IniData {
        if (!file.exists()) return IniData(emptyMap())
        return parse(file.readText())
    }

    fun parse(text: String): IniData {
        val sections = mutableMapOf<String, MutableMap<String, String>>()
        var currentSection = ""

        for (line in text.lines()) {
            val trimmed = line.trim()

            if (trimmed.isEmpty() || trimmed.startsWith(";")) continue

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1).trim()
                sections.getOrPut(currentSection) { mutableMapOf() }
                continue
            }

            val eqIndex = trimmed.indexOf('=')
            if (eqIndex > 0) {
                val key = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()
                sections.getOrPut(currentSection) { mutableMapOf() }[key] = value
            }
        }

        return IniData(sections)
    }
}

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
