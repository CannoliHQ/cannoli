package dev.cannoli.scorza.input.autoconfig

object DeviceAliases {

    const val KEY = "cannoli_device_aliases"

    fun parse(value: String): List<String> =
        value.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    fun format(aliases: List<String>): String = aliases.joinToString("|")
}
