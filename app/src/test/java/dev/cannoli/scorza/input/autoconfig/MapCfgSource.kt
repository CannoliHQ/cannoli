package dev.cannoli.scorza.input.autoconfig

import java.io.InputStream

/** In-memory [CfgSource] for tests. Lived in main source until 2026-08-16 with no production caller. */
class MapCfgSource(private val files: Map<String, String>) : CfgSource {
    override fun listCfgFiles(): List<String> = files.keys.toList()
    override fun open(name: String): InputStream =
        files[name]?.byteInputStream() ?: throw java.io.FileNotFoundException(name)
}
