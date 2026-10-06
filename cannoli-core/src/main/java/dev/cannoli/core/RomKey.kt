package dev.cannoli.core

import java.io.File
import java.text.Normalizer

object RomKey {
    fun baseName(path: File): String = normalize(path.nameWithoutExtension)

    fun normalize(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)
}
