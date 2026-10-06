package dev.cannoli.scorza.input.autoconfig

import java.io.InputStream

class MapCfgSource(private val files: Map<String, String>) : CfgSource {
    override fun listCfgFiles(): List<String> = files.keys.toList()
    override fun open(name: String): InputStream =
        files[name]?.byteInputStream() ?: throw java.io.FileNotFoundException(name)
}
