package dev.cannoli.scorza.input.autoconfig

import android.content.Context
import java.io.InputStream

interface CfgSource {
    fun listCfgFiles(): List<String>
    fun open(name: String): InputStream
}

class AssetCfgSource(private val context: Context, private val path: String = "autoconfig/android") : CfgSource {
    override fun listCfgFiles(): List<String> =
        (context.assets.list(path) ?: emptyArray())
            .filter { it.endsWith(".cfg", ignoreCase = true) }
            .map { "$path/$it" }

    override fun open(name: String): InputStream = context.assets.open(name)
}

class MapCfgSource(private val files: Map<String, String>) : CfgSource {
    override fun listCfgFiles(): List<String> = files.keys.toList()
    override fun open(name: String): InputStream =
        files[name]?.byteInputStream() ?: throw java.io.FileNotFoundException(name)
}
