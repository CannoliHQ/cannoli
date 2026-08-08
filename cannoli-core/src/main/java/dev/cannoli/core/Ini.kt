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
            if (tmp.renameTo(dest)) return
            // On the SD card MediaProvider keeps a database row per path, and renaming over a path
            // it already knows fails the transaction on a unique constraint. Clearing the
            // destination is what lets that rename through; nothing real is lost, because a rename
            // through FUSE was never atomic to begin with. Only after the plain rename has failed,
            // so the file is never taken away for a write that was going to land anyway.
            dest.delete()
            if (tmp.renameTo(dest)) return
            java.io.FileOutputStream(dest).use { fos ->
                fos.write(content.toByteArray())
                fos.fd.sync()
            }
            tmp.delete()
        } catch (_: Exception) {
            // The tmp is the only synced copy of this content, so it is kept when there is no
            // destination left to keep instead.
            if (dest.exists()) tmp.delete()
        }
    }
}
